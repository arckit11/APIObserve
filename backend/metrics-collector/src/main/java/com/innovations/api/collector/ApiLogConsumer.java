package com.innovations.api.collector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innovations.api.events.ApiLogPayload;
import com.innovations.api.events.EventEnvelope;
import com.innovations.api.events.Topics;
import com.innovations.api.util.DedupKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Consumes api-log events, validates them, and persists to Postgres. Kafka
 * guarantees at-least-once so we rely on the {@code dedup_key} unique index
 * to make writes idempotent.
 *
 * <p>Robustness and data safety guarantees:
 * <ul>
 *   <li>Distinguishes genuine duplicate-key collisions from real integrity errors (e.g. FK violations).</li>
 *   <li>Transient database errors (connection down, deadlock) are thrown without acknowledgment so Kafka retries them.</li>
 *   <li>Permanent invalid payloads are routed to the DLQ, and the offset is only acknowledged AFTER successful synchronous DLQ delivery.</li>
 *   <li>Full observability via Micrometer metrics counters and structured logging.</li>
 * </ul>
 */
@Component
public class ApiLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(ApiLogConsumer.class);

    private final ApiLogRepository repo;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, Object> kafka;
    private final String dlqTopic;

    private final Counter eventsConsumed;
    private final Counter eventsPersisted;
    private final Counter eventsDuplicate;
    private final Counter eventsDlq;
    private final Counter eventsTransientError;

    @Autowired
    public ApiLogConsumer(ApiLogRepository repo,
                          ObjectMapper mapper,
                          KafkaTemplate<String, Object> kafka,
                          @Value("${kafka.topics.api-logs-dlq:" + Topics.API_LOGS_DLQ + "}") String dlqTopic,
                          ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.repo = repo;
        this.mapper = mapper;
        this.kafka = kafka;
        this.dlqTopic = dlqTopic;

        MeterRegistry registry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        this.eventsConsumed = registry.counter("collector.events.consumed");
        this.eventsPersisted = registry.counter("collector.events.persisted");
        this.eventsDuplicate = registry.counter("collector.events.duplicate");
        this.eventsDlq = registry.counter("collector.events.dlq");
        this.eventsTransientError = registry.counter("collector.events.transient_error");
    }

    // Convenience constructor for tests
    public ApiLogConsumer(ApiLogRepository repo,
                          ObjectMapper mapper,
                          KafkaTemplate<String, Object> kafka,
                          String dlqTopic) {
        this(repo, mapper, kafka, dlqTopic, new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(MeterRegistry.class));
    }

    @KafkaListener(topics = "${kafka.topics.api-logs:" + Topics.API_LOGS + "}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        eventsConsumed.increment();
        try {
            EventEnvelope<ApiLogPayload> env = mapper.readValue(
                    record.value(),
                    new TypeReference<EventEnvelope<ApiLogPayload>>() {}
            );
            ApiLogPayload p = env.payload();
            if (p == null || p.serviceId() == null || p.endpoint() == null ||
                    p.timestamp() == null || p.httpMethod() == null) {
                throw new IllegalArgumentException("payload missing required fields");
            }

            String dedup = p.dedupKey() != null && !p.dedupKey().isBlank()
                    ? p.dedupKey()
                    : DedupKeys.forApiLog(env.eventId(), p.serviceId(), p.endpoint(),
                            p.httpMethod(), p.timestamp(), p.statusCode(), p.userId());

            if (repo.existsByDedupKey(dedup)) {
                eventsDuplicate.increment();
                log.debug("Event with dedup_key {} already exists (pre-check); skipping", dedup);
                ack.acknowledge();
                return;
            }

            ApiLogEntity e = new ApiLogEntity();
            e.setServiceId(p.serviceId());
            e.setEndpoint(p.endpoint());
            e.setHttpMethod(p.httpMethod());
            e.setStatusCode((short) p.statusCode());
            e.setResponseTimeMs(p.responseTimeMs());
            e.setUserAgent(p.userAgent());
            e.setClientIp(p.clientIp());
            e.setUserId(p.userId());
            e.setRequestBytes(p.requestBytes());
            e.setResponseBytes(p.responseBytes());
            e.setRequestTimestamp(p.timestamp());
            e.setDedupKey(dedup);

            try {
                repo.save(e);
                eventsPersisted.increment();
                ack.acknowledge();
            } catch (DataIntegrityViolationException dive) {
                if (isUniqueConstraintViolation(dive)) {
                    // Concurrent duplicate — another consumer worker processed it first.
                    eventsDuplicate.increment();
                    log.debug("Concurrent duplicate dedup_key {} ignored", dedup);
                    ack.acknowledge();
                } else {
                    // Foreign key or check constraint failure — unrecoverable bad data, route to DLQ
                    log.warn("Data integrity failure (non-duplicate) for dedup_key {}: {}", dedup, dive.getMessage());
                    routeToDlqAndWait(record, dive.getMessage(), ack);
                }
            } catch (TransientDataAccessException | CannotCreateTransactionException tdae) {
                eventsTransientError.increment();
                log.error("Transient database exception processing partition={} offset={}; throwing for retry",
                        record.partition(), record.offset(), tdae);
                throw tdae; // Do NOT ack — allow Kafka container to retry transient failure
            }
        } catch (TransientDataAccessException | CannotCreateTransactionException tdae) {
            throw tdae; // Propagate transient failures for retry
        } catch (Exception ex) {
            log.warn("Permanent failure processing event (partition={} offset={}): {}",
                    record.partition(), record.offset(), ex.getMessage());
            routeToDlqAndWait(record, ex.getMessage(), ack);
        }
    }

    private void routeToDlqAndWait(ConsumerRecord<String, byte[]> record, String reason, Acknowledgment ack) {
        try {
            DlqEnvelope dlq = new DlqEnvelope(
                    record.value() != null ? new String(record.value()) : "",
                    reason,
                    OffsetDateTime.now()
            );
            // Wait for DLQ publication confirmation before acknowledging to prevent data loss
            kafka.send(dlqTopic, record.key(), dlq).get(5, TimeUnit.SECONDS);
            eventsDlq.increment();
            ack.acknowledge();
            log.info("Successfully routed corrupt/unrecoverable event to DLQ (partition={} offset={})",
                    record.partition(), record.offset());
        } catch (Exception dlqEx) {
            log.error("CRITICAL: Failed to publish to DLQ for partition={} offset={}; refusing to acknowledge to prevent data loss: {}",
                    record.partition(), record.offset(), dlqEx.getMessage(), dlqEx);
            throw new RuntimeException("DLQ delivery failed; refusing to ack record", dlqEx);
        }
    }

    private boolean isUniqueConstraintViolation(DataIntegrityViolationException ex) {
        Throwable root = NestedExceptionUtils.getRootCause(ex);
        if (root != null && root.getMessage() != null) {
            String msg = root.getMessage().toLowerCase();
            if (msg.contains("23505") || msg.contains("unique") || msg.contains("dedup_key") || msg.contains("duplicate key")) {
                return true;
            }
        }
        String msg = ex.getMessage();
        return msg != null && (msg.toLowerCase().contains("dedup_key") || msg.toLowerCase().contains("unique"));
    }

    /** Minimal DLQ payload — original message + error reason + when. */
    public record DlqEnvelope(String original, String error, OffsetDateTime routedAt) {}
}
