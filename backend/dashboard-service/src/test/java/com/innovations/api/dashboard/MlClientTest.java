package com.innovations.api.dashboard;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MlClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MlClient mlClient;

    @BeforeEach
    void setUp() {
        mlClient = new MlClient(restTemplate, "http://ml-service:8000", redis);
    }

    @Test
    void testTimeoutConfigurationConstructor() {
        // Verify constructor with timeoutMs creates client without error
        MlClient clientWithTimeout = new MlClient("http://ml-service:8000", 1500, redis);
        assertNotNull(clientWithTimeout);
    }

    @Test
    void testHealthScoreSuccess() {
        UUID serviceId = UUID.randomUUID();
        Map<String, Object> snapshot = Map.of("request_count", 100);
        Map<String, Object> mockResp = Map.of("score", 95.0, "status", "healthy");

        when(restTemplate.postForObject(eq("http://ml-service:8000/score/health"), any(), eq(Map.class)))
                .thenReturn(mockResp);
        when(redis.opsForValue()).thenReturn(valueOperations);

        Map<String, Object> result = mlClient.healthScore(serviceId, snapshot);
        assertNotNull(result);
        assertEquals(95.0, result.get("score"));
    }

    @Test
    void testFallbackWhenMlTimesOut() {
        UUID serviceId = UUID.randomUUID();
        Throwable timeoutException = new ResourceAccessException("I/O error on POST request: Read timed out");

        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ml:last:health:" + serviceId)).thenReturn("{\"score\":80.0,\"status\":\"warning\"}");

        Map<String, Object> fallback = mlClient.healthFallback(serviceId, Map.of(), timeoutException);
        assertNotNull(fallback);
        assertTrue(Boolean.TRUE.equals(fallback.get("stale")));
        assertEquals(80.0, fallback.get("score"));
        assertEquals("warning", fallback.get("status"));
    }
}
