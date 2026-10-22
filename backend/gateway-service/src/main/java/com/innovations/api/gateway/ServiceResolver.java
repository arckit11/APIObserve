package com.innovations.api.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Resolves gateway route IDs to registered service UUIDs by periodically
 * fetching the full service list from the Registry Service.
 *
 * <p>The cached mapping is consulted synchronously by {@link TelemetryFilter}
 * on every captured request. Falls back to a configurable fallback service
 * ID when no mapping is found (e.g. registry unreachable or service not yet
 * registered).
 *
 * <p>Refresh runs on a {@code @Scheduled} thread — never on the Netty event
 * loop — so {@link WebClient#block()} is safe here.
 */
@Component
public class ServiceResolver {

    private static final Logger log = LoggerFactory.getLogger(ServiceResolver.class);

    /**
     * Maps gateway route IDs (from {@code application.yml} route definitions)
     * to registered service names in the {@code services} table. Update this
     * map when new routes are added to the gateway configuration.
     */
    private static final Map<String, String> ROUTE_TO_SERVICE = Map.of(
            "auth",      "auth-service",
            "registry",  "registry-service",
            "analytics", "analytics-service",
            "dashboard", "dashboard-service"
    );

    private final WebClient registryClient;
    private final UUID fallbackServiceId;

    /** service-name → service UUID, refreshed periodically. */
    private final ConcurrentHashMap<String, UUID> cache = new ConcurrentHashMap<>();

    public ServiceResolver(
            @Value("${gateway.registry-url:http://registry-service:8082}") String registryUrl,
            @Value("${gateway.fallback-service-id:00000000-0000-0000-0000-00000000feed}") String fallbackId) {
        this.registryClient = WebClient.create(registryUrl);
        this.fallbackServiceId = UUID.fromString(fallbackId);
    }

    /**
     * Periodically fetch the full service list from the Registry Service and
     * rebuild the local name→UUID cache. Only services with
     * {@code capture_telemetry = true} are cached.
     */
    @Scheduled(fixedDelayString = "${gateway.registry-refresh-ms:60000}",
               initialDelayString = "${gateway.registry-initial-delay-ms:5000}")
    public void refresh() {
        try {
            List<RegistryEntry> services = registryClient.get()
                    .uri("/registry/services")
                    .retrieve()
                    .bodyToFlux(RegistryEntry.class)
                    .collectList()
                    .block(Duration.ofSeconds(5));
            if (services != null) {
                ConcurrentHashMap<String, UUID> next = new ConcurrentHashMap<>();
                for (RegistryEntry s : services) {
                    if (s.id() != null && s.name() != null && s.captureTelemetry()) {
                        next.put(s.name(), s.id());
                    }
                }
                cache.clear();
                cache.putAll(next);
                log.info("Refreshed service registry: {} services cached", cache.size());
            }
        } catch (Exception e) {
            log.warn("Failed to refresh service registry (will retry): {}", e.getMessage());
        }
    }

    void register(String serviceName, UUID id) {
        cache.put(serviceName, id);
    }

    /**
     * Resolve a gateway route ID to the registered service UUID.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>Route ID → service name via {@link #ROUTE_TO_SERVICE} → UUID from cache</li>
     *   <li>Route ID used directly as service name → UUID from cache</li>
     *   <li>First path segment used as route key (same lookup as step 1)</li>
     *   <li>Fallback service ID</li>
     * </ol>
     *
     * @param routeId matched gateway route ID (e.g. "auth", "registry"), may be null
     * @param path    request path, used as secondary lookup via first segment
     * @return the registered service UUID, or the fallback ID
     */
    public UUID resolve(String routeId, String path) {
        // Primary: route ID → service name → UUID
        if (routeId != null) {
            String serviceName = ROUTE_TO_SERVICE.get(routeId);
            if (serviceName != null) {
                UUID id = cache.get(serviceName);
                if (id != null) return id;
            }
            // Try routeId as a direct service name
            UUID direct = cache.get(routeId);
            if (direct != null) return direct;
        }

        // Secondary: first path segment as a route key
        if (path != null && path.length() > 1) {
            String segment = path.substring(1); // strip leading /
            int slash = segment.indexOf('/');
            if (slash > 0) segment = segment.substring(0, slash);
            String serviceName = ROUTE_TO_SERVICE.get(segment);
            if (serviceName != null) {
                UUID id = cache.get(serviceName);
                if (id != null) return id;
            }
        }

        return fallbackServiceId;
    }

    /** Minimal projection of the Registry Service response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegistryEntry(UUID id, String name,
                         @JsonProperty("capture_telemetry") boolean captureTelemetry) {}
}
