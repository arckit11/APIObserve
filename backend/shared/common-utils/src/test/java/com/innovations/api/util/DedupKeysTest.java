package com.innovations.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DedupKeysTest {

    @Test
    void sameSecondDifferentMethodsDoNotCollide() {
        UUID serviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        String getKey = DedupKeys.forApiLog(serviceId, "/orders", "GET", now, 200, userId);
        String postKey = DedupKeys.forApiLog(serviceId, "/orders", "POST", now, 200, userId);

        assertNotEquals(getKey, postKey, "GET and POST requests to the same endpoint should have distinct keys");
    }

    @Test
    void sameSecondDistinctEventIdsDoNotCollide() {
        UUID serviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID event1 = UUID.randomUUID();
        UUID event2 = UUID.randomUUID();

        String key1 = DedupKeys.forApiLog(event1, serviceId, "/orders", "POST", now, 200, userId);
        String key2 = DedupKeys.forApiLog(event2, serviceId, "/orders", "POST", now, 200, userId);

        assertNotEquals(key1, key2, "Two distinct requests in the same second must have distinct dedup keys");
    }

    @Test
    void redeliveredEventProducesIdenticalKey() {
        UUID serviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID eventId = UUID.randomUUID();

        String keyFirst = DedupKeys.forApiLog(eventId, serviceId, "/orders", "POST", now, 200, userId);
        String keyRetried = DedupKeys.forApiLog(eventId, serviceId, "/orders", "POST", now, 200, userId);

        assertEquals(keyFirst, keyRetried, "Redelivered Kafka event must yield the exact same dedup key");
    }
}
