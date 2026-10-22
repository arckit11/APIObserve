package com.innovations.api.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.innovations.api.security.JwtProperties;
import com.innovations.api.security.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class JwtAuthFilterTest {

    private JwtService jwtService;
    private JwtAuthFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(
                "01234567890123456789012345678901", // 32 bytes for HS256
                "innovations-api",
                3600,
                86400
        );
        jwtService = new JwtService(props);
        filter = new JwtAuthFilter(jwtService);
        chain = exchange -> Mono.empty();
    }

    @Test
    void openPrefixesBypassAuth() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/auth/login").build()
        );
        filter.filter(exchange, chain).block();
        assertEquals(null, exchange.getResponse().getStatusCode());
    }

    @Test
    void missingTokenReturns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/dashboard/health").build()
        );
        filter.filter(exchange, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void viewerCanReadDashboard() {
        String token = jwtService.issueAccessToken(UUID.randomUUID(), "viewer_user", List.of("VIEWER"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/dashboard/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );
        filter.filter(exchange, chain).block();
        assertEquals(null, exchange.getResponse().getStatusCode());
    }

    @Test
    void viewerCannotMutateRegistry() {
        String token = jwtService.issueAccessToken(UUID.randomUUID(), "viewer_user", List.of("VIEWER"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/registry/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );
        filter.filter(exchange, chain).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void developerCanMutateRegistry() {
        String token = jwtService.issueAccessToken(UUID.randomUUID(), "dev_user", List.of("DEVELOPER"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/registry/services")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );
        filter.filter(exchange, chain).block();
        assertEquals(null, exchange.getResponse().getStatusCode());
    }

    @Test
    void developerCannotAccessAdminRoutes() {
        String token = jwtService.issueAccessToken(UUID.randomUUID(), "dev_user", List.of("DEVELOPER"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );
        filter.filter(exchange, chain).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void adminCanAccessAdminRoutes() {
        String token = jwtService.issueAccessToken(UUID.randomUUID(), "admin_user", List.of("ADMIN"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );
        filter.filter(exchange, chain).block();
        assertEquals(null, exchange.getResponse().getStatusCode());
    }
}
