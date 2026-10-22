package com.innovations.api.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Stable dedup-key generation for api-log rows.
 *
 * <p>The composite identity incorporates the unique event/request ID, service ID,
 * endpoint, HTTP method, timestamp (truncated to seconds), status code, and user ID.
 * Incorporating the unique event ID and HTTP method guarantees that legitimate same-second
 * requests (even with identical users/status codes, or differing HTTP methods like GET vs POST)
 * never collide, while at-least-once Kafka retries of the exact same event produce identical
 * keys and are safely deduplicated.
 */
public final class DedupKeys {

    private DedupKeys() {}

    /**
     * Primary dedup-key generator for API events.
     *
     * @param eventId    unique ID of the ingestion event / request
     * @param serviceId  monitored service UUID
     * @param endpoint   request URI path
     * @param httpMethod HTTP method (GET, POST, etc.)
     * @param timestamp  request timestamp
     * @param statusCode HTTP response status code
     * @param userId     authenticated user UUID (or null)
     * @return 64-character SHA-256 hex string
     */
    public static String forApiLog(UUID eventId,
                                   UUID serviceId,
                                   String endpoint,
                                   String httpMethod,
                                   OffsetDateTime timestamp,
                                   int statusCode,
                                   UUID userId) {
        String canonical = String.join("|",
                eventId != null ? eventId.toString() : "",
                String.valueOf(serviceId),
                endpoint == null ? "" : endpoint,
                httpMethod == null ? "" : httpMethod.toUpperCase(),
                timestamp != null
                        ? timestamp.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString()
                        : "",
                String.valueOf(statusCode),
                userId == null ? "" : userId.toString()
        );
        return sha256Hex(canonical);
    }

    /**
     * Overload for callers without an event ID. Includes HTTP method.
     */
    public static String forApiLog(UUID serviceId,
                                   String endpoint,
                                   String httpMethod,
                                   OffsetDateTime timestamp,
                                   int statusCode,
                                   UUID userId) {
        return forApiLog(null, serviceId, endpoint, httpMethod, timestamp, statusCode, userId);
    }

    /**
     * Backwards-compatible overload.
     */
    public static String forApiLog(UUID serviceId,
                                   String endpoint,
                                   OffsetDateTime timestamp,
                                   int statusCode,
                                   UUID userId) {
        return forApiLog(null, serviceId, endpoint, null, timestamp, statusCode, userId);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK spec — treat as fatal.
            throw new IllegalStateException("SHA-256 not available in JRE", e);
        }
    }
}
