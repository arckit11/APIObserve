package com.innovations.api.collector;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.innovations.api.events.ApiLogPayload;
import com.innovations.api.events.EventEnvelope;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

class ApiLogConsumerTest {

    private ApiLogRepository repo;
    private ObjectMapper mapper;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, Object> kafka;
    private Acknowledgment ack;
    private ApiLogConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(ApiLogRepository.class);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        kafka = mock(KafkaTemplate.class);
        ack = mock(Acknowledgment.class);
        consumer = new ApiLogConsumer(repo, mapper, kafka, "api-logs-dlq");
    }

    private ConsumerRecord<String, byte[]> createRecord(ApiLogPayload payload) throws Exception {
        EventEnvelope<ApiLogPayload> env = EventEnvelope.of("api-log", "gateway", payload);
        byte[] bytes = mapper.writeValueAsBytes(env);
        return new ConsumerRecord<>("api-logs", 0, 100L, "key", bytes);
    }

    @Test
    void validMessagePersistedAndAcked() throws Exception {
        UUID serviceId = UUID.randomUUID();
        ApiLogPayload payload = new ApiLogPayload(
                serviceId, "/test", "GET", 200, 50,
                "agent", "127.0.0.1", null, 10, 100, OffsetDateTime.now(), "dedup123"
        );
        ConsumerRecord<String, byte[]> record = createRecord(payload);
        when(repo.existsByDedupKey("dedup123")).thenReturn(false);

        consumer.consume(record, ack);

        verify(repo).save(any(ApiLogEntity.class));
        verify(ack).acknowledge();
    }

    @Test
    void preExistingDuplicateIgnoredAndAcked() throws Exception {
        UUID serviceId = UUID.randomUUID();
        ApiLogPayload payload = new ApiLogPayload(
                serviceId, "/test", "GET", 200, 50,
                "agent", "127.0.0.1", null, 10, 100, OffsetDateTime.now(), "dedup123"
        );
        ConsumerRecord<String, byte[]> record = createRecord(payload);
        when(repo.existsByDedupKey("dedup123")).thenReturn(true);

        consumer.consume(record, ack);

        verify(repo, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    void uniqueConstraintRaceIgnoredAndAcked() throws Exception {
        UUID serviceId = UUID.randomUUID();
        ApiLogPayload payload = new ApiLogPayload(
                serviceId, "/test", "GET", 200, 50,
                "agent", "127.0.0.1", null, 10, 100, OffsetDateTime.now(), "dedup123"
        );
        ConsumerRecord<String, byte[]> record = createRecord(payload);
        when(repo.existsByDedupKey("dedup123")).thenReturn(false);
        when(repo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"api_logs_dedup_key_key\""));

        consumer.consume(record, ack);

        verify(ack).acknowledge();
    }

    @Test
    void nonDuplicateIntegrityViolationRoutesToDlq() throws Exception {
        UUID serviceId = UUID.randomUUID();
        ApiLogPayload payload = new ApiLogPayload(
                serviceId, "/test", "GET", 200, 50,
                "agent", "127.0.0.1", null, 10, 100, OffsetDateTime.now(), "dedup123"
        );
        ConsumerRecord<String, byte[]> record = createRecord(payload);
        when(repo.existsByDedupKey("dedup123")).thenReturn(false);
        when(repo.save(any())).thenThrow(new DataIntegrityViolationException("violates foreign key constraint \"services_fk\""));
        when(kafka.send(eq("api-logs-dlq"), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        consumer.consume(record, ack);

        verify(kafka).send(eq("api-logs-dlq"), eq("key"), any());
        verify(ack).acknowledge();
    }

    @Test
    void transientDbExceptionThrowsAndDoesNotAck() throws Exception {
        UUID serviceId = UUID.randomUUID();
        ApiLogPayload payload = new ApiLogPayload(
                serviceId, "/test", "GET", 200, 50,
                "agent", "127.0.0.1", null, 10, 100, OffsetDateTime.now(), "dedup123"
        );
        ConsumerRecord<String, byte[]> record = createRecord(payload);
        when(repo.existsByDedupKey("dedup123")).thenReturn(false);
        when(repo.save(any())).thenThrow(new TransientDataAccessResourceException("Connection lost"));

        assertThrows(TransientDataAccessResourceException.class, () -> consumer.consume(record, ack));
        verify(ack, never()).acknowledge();
        verify(kafka, never()).send(eq("api-logs-dlq"), any(), any());
    }
}
