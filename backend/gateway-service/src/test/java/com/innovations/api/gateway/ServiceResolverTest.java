package com.innovations.api.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServiceResolverTest {

    private static final String FALLBACK_ID_STR = "00000000-0000-0000-0000-00000000feed";
    private static final UUID FALLBACK_ID = UUID.fromString(FALLBACK_ID_STR);
    private static final UUID AUTH_ID = UUID.fromString("a0000001-0000-0000-0000-000000000001");
    private static final UUID REGISTRY_ID = UUID.fromString("a0000002-0000-0000-0000-000000000002");
    private static final UUID PAYMENTS_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ServiceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ServiceResolver("http://localhost:8082", FALLBACK_ID_STR);
    }

    @Test
    void resolvesFallbackWhenCacheEmpty() {
        UUID resolved = resolver.resolve("unknown", "/unknown/endpoint");
        assertEquals(FALLBACK_ID, resolved);
    }

    @Test
    void resolvesByRouteIdMapping() {
        resolver.register("auth-service", AUTH_ID);
        // "auth" route ID maps to "auth-service"
        UUID resolved = resolver.resolve("auth", "/auth/login");
        assertEquals(AUTH_ID, resolved);
    }

    @Test
    void resolvesByDirectServiceName() {
        resolver.register("payments-api", PAYMENTS_ID);
        UUID resolved = resolver.resolve("payments-api", "/orders");
        assertEquals(PAYMENTS_ID, resolved);
    }

    @Test
    void resolvesByFirstPathSegmentFallback() {
        resolver.register("registry-service", REGISTRY_ID);
        // Route ID null, but path starts with /registry/services
        UUID resolved = resolver.resolve(null, "/registry/services");
        assertEquals(REGISTRY_ID, resolved);
    }
}
