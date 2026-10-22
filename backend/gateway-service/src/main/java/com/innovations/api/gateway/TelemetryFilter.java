package com.innovations.api.gateway;

import com.innovations.api.events.ApiLogPayload;
import com.innovations.api.events.EventEnvelope;
import com.innovations.api.events.Topics;
import com.innovations.api.util.DedupKeys;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Records every forwarded request as an {@code api-logs} event. Runs after
 * {@link JwtAuthFilter} so it can attribute traffic to a user, but the
 * capture itself is best-effort — a Kafka publish failure never blocks the
 * response.
 *
 * <p>Service resolution maps matched routes and path segments to registered
 * service UUIDs via {@link ServiceResolver}.
 */
@Component
public class TelemetryFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TelemetryFilter.class);

    private final KafkaTemplate<String, Object> kafka;
    private final String topic;
    private final ServiceResolver serviceResolver;
    private final String producerName;
    private final io.micrometer.core.instrument.Counter publishedCounter;
    private final io.micrometer.core.instrument.Counter droppedCounter;

    public TelemetryFilter(KafkaTemplate<String, Object> kafka,
                           @Value("${kafka.topics.api-logs:" + Topics.API_LOGS + "}") String topic,
                           ServiceResolver serviceResolver,
                           org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider) {
        this.kafka = kafka;
        this.topic = topic;
        this.serviceResolver = serviceResolver;
        this.producerName = "gateway-service";

        io.micrometer.core.instrument.MeterRegistry registry = meterRegistryProvider.getIfAvailable(
                io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        this.publishedCounter = registry.counter("gateway.telemetry.published");
        this.droppedCounter = registry.counter("gateway.telemetry.dropped");
    }

    // Convenience constructor for tests
    public TelemetryFilter(KafkaTemplate<String, Object> kafka,
                           String topic,
                           ServiceResolver serviceResolver) {
        this(kafka, topic, serviceResolver, new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(sig -> capture(exchange, start));
    }

    private void capture(ServerWebExchange exchange, long startNanos) {
        try {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            OffsetDateTime ts = OffsetDateTime.now();
            String path = exchange.getRequest().getPath().value();
            String method = exchange.getRequest().getMethod().name();
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 0;

            String userIdHeader = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            UUID userId = userIdHeader != null ? safeUuid(userIdHeader) : null;
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route != null ? route.getId() : null;
            UUID serviceId = serviceResolver.resolve(routeId, path);

            UUID eventId = UUID.randomUUID();
            String dedup = DedupKeys.forApiLog(eventId, serviceId, path, method, ts, status, userId);
            ApiLogPayload payload = new ApiLogPayload(
                    serviceId, path, method, status, (int) elapsedMs,
                    exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT),
                    exchange.getRequest().getRemoteAddress() != null
                            ? exchange.getRequest().getRemoteAddress().getHostString() : null,
                    userId, null, null, ts, dedup
            );
            EventEnvelope<ApiLogPayload> env = new EventEnvelope<>(
                    eventId, "api-log", "1", ts, producerName, null, payload
            );

            kafka.send(topic, serviceId.toString(), env).whenComplete((result, ex) -> {
                if (ex != null) {
                    droppedCounter.increment();
                    log.error("Asynchronous telemetry publish to Kafka topic '{}' failed for serviceId={}: {}",
                            topic, serviceId, ex.getMessage(), ex);
                } else {
                    publishedCounter.increment();
                    log.trace("Telemetry published to topic={} partition={} offset={}",
                            topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
        } catch (Exception e) {
            droppedCounter.increment();
            log.error("Synchronous telemetry dispatch failed: {}", e.getMessage(), e);
        }
    }

    private static UUID safeUuid(String v) {
        try { return UUID.fromString(v); } catch (IllegalArgumentException e) { return null; }
    }

    @Override
    public int getOrder() {
        return 0; // after auth
    }
}
