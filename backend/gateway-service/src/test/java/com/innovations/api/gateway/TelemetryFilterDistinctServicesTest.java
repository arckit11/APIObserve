package com.innovations.api.gateway;

import com.innovations.api.events.ApiLogPayload;
import com.innovations.api.events.EventEnvelope;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryFilterDistinctServicesTest {

    @Mock
    private KafkaTemplate<String, Object> kafka;

    @Mock
    private ServiceResolver serviceResolver;

    @Mock
    private GatewayFilterChain chain;

    private TelemetryFilter filter;

    private final UUID paymentsServiceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID inventoryServiceId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        filter = new TelemetryFilter(kafka, "api-logs", serviceResolver);
        when(chain.filter(any())).thenReturn(Mono.empty());
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
    }

    @Test
    void distinctRegisteredServicesProduceDistinctTelemetryEvents() {
        Route paymentsRoute = Route.async()
                .id("payments")
                .uri(URI.create("http://payments.internal"))
                .order(0)
                .predicate(exchange -> true)
                .build();

        Route inventoryRoute = Route.async()
                .id("inventory")
                .uri(URI.create("http://inventory.internal"))
                .order(1)
                .predicate(exchange -> true)
                .build();

        when(serviceResolver.resolve("payments", "/payments/charge")).thenReturn(paymentsServiceId);
        when(serviceResolver.resolve("inventory", "/inventory/items")).thenReturn(inventoryServiceId);

        // 1. Process payments request
        MockServerHttpRequest req1 = MockServerHttpRequest.post("/payments/charge").build();
        MockServerWebExchange exchange1 = MockServerWebExchange.from(req1);
        exchange1.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, paymentsRoute);
        exchange1.getResponse().setRawStatusCode(200);

        filter.filter(exchange1, chain).block();

        // 2. Process inventory request
        MockServerHttpRequest req2 = MockServerHttpRequest.get("/inventory/items").build();
        MockServerWebExchange exchange2 = MockServerWebExchange.from(req2);
        exchange2.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, inventoryRoute);
        exchange2.getResponse().setRawStatusCode(200);

        filter.filter(exchange2, chain).block();

        // Capture Kafka publishing calls
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        verify(kafka, atLeastOnce()).send(topicCaptor.capture(), keyCaptor.capture(), payloadCaptor.capture());

        var payloads = payloadCaptor.getAllValues();
        assertEquals(2, payloads.size());

        EventEnvelope<?> env1 = (EventEnvelope<?>) payloads.get(0);
        EventEnvelope<?> env2 = (EventEnvelope<?>) payloads.get(1);

        ApiLogPayload event1 = (ApiLogPayload) env1.payload();
        ApiLogPayload event2 = (ApiLogPayload) env2.payload();

        // Assert distinct service IDs
        assertEquals(paymentsServiceId, event1.serviceId());
        assertEquals(inventoryServiceId, event2.serviceId());
        assertNotEquals(event1.serviceId(), event2.serviceId());

        // Assert distinct endpoints & methods
        assertEquals("/payments/charge", event1.endpoint());
        assertEquals("POST", event1.httpMethod());

        assertEquals("/inventory/items", event2.endpoint());
        assertEquals("GET", event2.httpMethod());

        assertNotNull(env1.eventId());
        assertNotNull(env2.eventId());
        assertNotEquals(env1.eventId(), env2.eventId());

        assertNotNull(event1.dedupKey());
        assertNotNull(event2.dedupKey());
        assertNotEquals(event1.dedupKey(), event2.dedupKey());
    }
}
