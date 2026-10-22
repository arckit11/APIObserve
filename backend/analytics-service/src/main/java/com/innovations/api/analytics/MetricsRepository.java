package com.innovations.api.analytics;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-based repository — analytics uses raw aggregation SQL so we don't
 * pay the JPA hydration cost for high-fanout reads and don't need to
 * express every window function as JPQL.
 *
 * <p>Aggregations LEFT JOIN from {@code services} so every monitored service
 * has a complete service-by-minute timeline, distinguishing zero-traffic
 * minutes from missing or unobserved data.
 */
@Repository
public class MetricsRepository {

    private final JdbcTemplate jdbc;

    public MetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Aggregate {@code api_logs} into 1-minute windows for all monitored services.
     * Uses LEFT JOIN so zero-traffic minutes are explicitly recorded with 0 requests
     * rather than missing completely.
     */
    public int aggregateOneMinuteWindow(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        String sql = """
            INSERT INTO metrics (
                service_id, window_start, window_end, window_size,
                request_count, rpm,
                response_time_mean, response_time_p95, response_time_p99,
                error_rate, success_rate,
                computed_at
            )
            SELECT
                s.id AS service_id,
                ?::timestamptz AS window_start,
                ?::timestamptz AS window_end,
                '1m' AS window_size,
                COALESCE(COUNT(l.id), 0) AS request_count,
                COALESCE(COUNT(l.id), 0) AS rpm,
                COALESCE(AVG(l.response_time_ms)::numeric(10,2), 0.00) AS response_time_mean,
                COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY l.response_time_ms)::numeric(10,2), 0.00) AS p95,
                COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY l.response_time_ms)::numeric(10,2), 0.00) AS p99,
                COALESCE((SUM(CASE WHEN l.status_code >= 400 THEN 1 ELSE 0 END)::numeric
                    / NULLIF(COUNT(l.id), 0))::numeric(5,4), 0.0000) AS error_rate,
                COALESCE((SUM(CASE WHEN l.status_code < 400 THEN 1 ELSE 0 END)::numeric
                    / NULLIF(COUNT(l.id), 0))::numeric(5,4), 1.0000) AS success_rate,
                NOW()
            FROM services s
            LEFT JOIN api_logs l ON l.service_id = s.id
                AND l.request_timestamp >= ?::timestamptz
                AND l.request_timestamp <  ?::timestamptz
            WHERE s.capture_telemetry = true
            GROUP BY s.id
            ON CONFLICT (service_id, window_size, window_start)
                DO UPDATE SET
                    request_count = EXCLUDED.request_count,
                    rpm = EXCLUDED.rpm,
                    response_time_mean = EXCLUDED.response_time_mean,
                    response_time_p95 = EXCLUDED.response_time_p95,
                    response_time_p99 = EXCLUDED.response_time_p99,
                    error_rate = EXCLUDED.error_rate,
                    success_rate = EXCLUDED.success_rate,
                    computed_at = NOW()
            """;
        return jdbc.update(sql, windowStart, windowEnd, windowStart, windowEnd);
    }

    /**
     * Aggregate {@code api_logs} into 5-minute windows for all monitored services.
     */
    public int aggregateFiveMinuteWindow(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        String sql = """
            INSERT INTO metrics (
                service_id, window_start, window_end, window_size,
                request_count, rpm,
                response_time_mean, response_time_p95, response_time_p99,
                error_rate, success_rate,
                computed_at
            )
            SELECT
                s.id AS service_id,
                ?::timestamptz AS window_start,
                ?::timestamptz AS window_end,
                '5m' AS window_size,
                COALESCE(COUNT(l.id), 0) AS request_count,
                COALESCE((COUNT(l.id)::numeric / 5.0)::numeric(10,2), 0.00) AS rpm,
                COALESCE(AVG(l.response_time_ms)::numeric(10,2), 0.00) AS response_time_mean,
                COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY l.response_time_ms)::numeric(10,2), 0.00) AS p95,
                COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY l.response_time_ms)::numeric(10,2), 0.00) AS p99,
                COALESCE((SUM(CASE WHEN l.status_code >= 400 THEN 1 ELSE 0 END)::numeric
                    / NULLIF(COUNT(l.id), 0))::numeric(5,4), 0.0000) AS error_rate,
                COALESCE((SUM(CASE WHEN l.status_code < 400 THEN 1 ELSE 0 END)::numeric
                    / NULLIF(COUNT(l.id), 0))::numeric(5,4), 1.0000) AS success_rate,
                NOW()
            FROM services s
            LEFT JOIN api_logs l ON l.service_id = s.id
                AND l.request_timestamp >= ?::timestamptz
                AND l.request_timestamp <  ?::timestamptz
            WHERE s.capture_telemetry = true
            GROUP BY s.id
            ON CONFLICT (service_id, window_size, window_start)
                DO UPDATE SET
                    request_count = EXCLUDED.request_count,
                    rpm = EXCLUDED.rpm,
                    response_time_mean = EXCLUDED.response_time_mean,
                    response_time_p95 = EXCLUDED.response_time_p95,
                    response_time_p99 = EXCLUDED.response_time_p99,
                    error_rate = EXCLUDED.error_rate,
                    success_rate = EXCLUDED.success_rate,
                    computed_at = NOW()
            """;
        return jdbc.update(sql, windowStart, windowEnd, windowStart, windowEnd);
    }

    /**
     * Aggregate {@code api_logs} into 1-hour windows for all monitored services.
     */
    public int aggregateOneHourWindow(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        String sql = """
            INSERT INTO metrics (
                service_id, window_start, window_end, window_size,
                request_count, rpm,
                response_time_mean, response_time_p95, response_time_p99,
                error_rate, success_rate,
                computed_at
            )
            SELECT
                s.id AS service_id,
                ?::timestamptz AS window_start,
                ?::timestamptz AS window_end,
                '1h' AS window_size,
                COALESCE(COUNT(l.id), 0) AS request_count,
                COALESCE((COUNT(l.id)::numeric / 60.0)::numeric(10,2), 0.00) AS rpm,
                COALESCE(AVG(l.response_time_ms)::numeric(10,2), 0.00) AS response_time_mean,
                COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY l.response_time_ms)::numeric(10,2), 0.00) AS p95,
                COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY l.response_time_ms)::numeric(10,2), 0.00) AS p99,
                COALESCE((SUM(CASE WHEN l.status_code >= 400 THEN 1 ELSE 0 END)::numeric
                    / NULLIF(COUNT(l.id), 0))::numeric(5,4), 0.0000) AS error_rate,
                COALESCE((SUM(CASE WHEN l.status_code < 400 THEN 1 ELSE 0 END)::numeric
                    / NULLIF(COUNT(l.id), 0))::numeric(5,4), 1.0000) AS success_rate,
                NOW()
            FROM services s
            LEFT JOIN api_logs l ON l.service_id = s.id
                AND l.request_timestamp >= ?::timestamptz
                AND l.request_timestamp <  ?::timestamptz
            WHERE s.capture_telemetry = true
            GROUP BY s.id
            ON CONFLICT (service_id, window_size, window_start)
                DO UPDATE SET
                    request_count = EXCLUDED.request_count,
                    rpm = EXCLUDED.rpm,
                    response_time_mean = EXCLUDED.response_time_mean,
                    response_time_p95 = EXCLUDED.response_time_p95,
                    response_time_p99 = EXCLUDED.response_time_p99,
                    error_rate = EXCLUDED.error_rate,
                    success_rate = EXCLUDED.success_rate,
                    computed_at = NOW()
            """;
        return jdbc.update(sql, windowStart, windowEnd, windowStart, windowEnd);
    }

    /** Latest 1-minute metric row per service, ordered by service_id. */
    public List<MetricAggregate> latestPerService() {
        String sql = """
            SELECT DISTINCT ON (service_id)
                service_id, window_start, window_end, window_size,
                request_count, rpm,
                response_time_mean, response_time_p95, response_time_p99,
                error_rate, success_rate
            FROM metrics
            WHERE window_size = '1m'
            ORDER BY service_id, window_start DESC
            """;
        return jdbc.query(sql, MetricsRepository::mapAggregate);
    }

    public List<java.util.Map<String, Object>> openAlerts(int limit) {
        String sql = """
            SELECT id, service_id, triggering_metric, severity, priority,
                   message, opened_at
            FROM alerts
            WHERE closed_at IS NULL
            ORDER BY opened_at DESC
            LIMIT ?
            """;
        return jdbc.query(sql, (rs, i) -> {
            java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", rs.getObject("id", UUID.class));
            row.put("service_id", rs.getObject("service_id", UUID.class));
            row.put("triggering_metric", rs.getString("triggering_metric"));
            row.put("severity", rs.getString("severity"));
            row.put("priority", rs.getString("priority"));
            row.put("message", rs.getString("message"));
            row.put("opened_at", rs.getObject("opened_at", OffsetDateTime.class));
            return row;
        }, limit);
    }

    public List<MetricAggregate> history(UUID serviceId, OffsetDateTime from,
                                         OffsetDateTime to, String windowSize) {
        String sql = """
            SELECT service_id, window_start, window_end, window_size,
                   request_count, rpm,
                   response_time_mean, response_time_p95, response_time_p99,
                   error_rate, success_rate
            FROM metrics
            WHERE service_id = ?
              AND window_size = ?
              AND window_start >= ?::timestamptz
              AND window_start <  ?::timestamptz
            ORDER BY window_start
            """;
        return jdbc.query(sql, MetricsRepository::mapAggregate,
                serviceId, windowSize, from, to);
    }

    private static MetricAggregate mapAggregate(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new MetricAggregate(
                UUID.fromString(rs.getString("service_id")),
                rs.getObject("window_start", OffsetDateTime.class),
                rs.getObject("window_end", OffsetDateTime.class),
                rs.getString("window_size"),
                rs.getInt("request_count"),
                rs.getBigDecimal("rpm"),
                rs.getBigDecimal("response_time_mean"),
                rs.getBigDecimal("response_time_p95"),
                rs.getBigDecimal("response_time_p99"),
                rs.getBigDecimal("error_rate"),
                rs.getBigDecimal("success_rate")
        );
    }
}
