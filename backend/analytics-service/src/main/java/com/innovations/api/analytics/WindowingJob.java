package com.innovations.api.analytics;

import com.innovations.api.events.EventEnvelope;
import com.innovations.api.events.MetricPayload;
import com.innovations.api.events.Topics;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs periodically to compute windowed metric aggregates (1m, 5m, 1h).
 *
 * <p>Key temporal reliability improvements:
 * <ul>
 *   <li>Runs with a 15-second grace window (:15) so in-flight Kafka ingestion can settle.</li>
 *   <li>Recomputes the immediately preceding window on each pass to absorb late-arriving events.</li>
 *   <li>Computes 5-minute and 1-hour rollup windows on schedule.</li>
 * </ul>
 */
@Component
public class WindowingJob {

    private static final Logger log = LoggerFactory.getLogger(WindowingJob.class);

    private final MetricsRepository repo;
    private final KafkaTemplate<String, Object> kafka;
    private final String metricsTopic;

    public WindowingJob(MetricsRepository repo,
                        KafkaTemplate<String, Object> kafka,
                        @Value("${kafka.topics.metrics:" + Topics.METRICS + "}") String metricsTopic) {
        this.repo = repo;
        this.kafka = kafka;
        this.metricsTopic = metricsTopic;
    }

    /**
     * Runs 15 seconds after the minute boundary to provide a grace period
     * for in-flight events, recomputes the prior window [T-2m, T-1m) for late arrivals,
     * and aggregates [T-1m, T).
     */
    @Scheduled(cron = "15 * * * * *")
    public void aggregateMinute() {
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        OffsetDateTime start = end.minusMinutes(1);

        // 1. Recompute previous window [T-2m, T-1m) to catch late events
        OffsetDateTime prevStart = start.minusMinutes(1);
        try {
            repo.aggregateOneMinuteWindow(prevStart, start);
        } catch (Exception ex) {
            log.warn("Late-event recomputation failed for window {} → {}: {}", prevStart, start, ex.getMessage());
        }

        // 2. Aggregate current window [T-1m, T)
        int rows = repo.aggregateOneMinuteWindow(start, end);
        log.debug("Aggregated 1m window {} → {}: {} services", start, end, rows);

        // 3. Fan the fresh rows out to the metrics topic
        List<MetricAggregate> latest = repo.latestPerService();
        for (MetricAggregate m : latest) {
            if (!start.isEqual(m.windowStart())) continue;
            MetricPayload payload = new MetricPayload(
                    m.serviceId(), m.windowStart(), m.windowEnd(), m.windowSize(),
                    m.requestCount(), m.rpm(), m.responseTimeMean(),
                    m.responseTimeP95(), m.responseTimeP99(),
                    m.errorRate(), m.successRate()
            );
            EventEnvelope<MetricPayload> env = EventEnvelope.of("metric", "analytics-service", payload);
            kafka.send(metricsTopic, m.serviceId().toString(), env);
        }
    }

    /**
     * Runs every 5 minutes at :30 seconds to aggregate 5-minute windows.
     */
    @Scheduled(cron = "30 */5 * * * *")
    public void aggregateFiveMinutes() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int minute = now.getMinute() - (now.getMinute() % 5);
        OffsetDateTime end = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(minute);
        OffsetDateTime start = end.minusMinutes(5);

        // Recompute previous 5m window and aggregate current 5m window
        try {
            repo.aggregateFiveMinuteWindow(start.minusMinutes(5), start);
        } catch (Exception ex) {
            log.warn("Late-event 5m recomputation failed: {}", ex.getMessage());
        }

        int rows = repo.aggregateFiveMinuteWindow(start, end);
        log.info("Aggregated 5m window {} → {}: {} rows", start, end, rows);
    }

    /**
     * Runs hourly at :02 minutes to aggregate 1-hour windows.
     */
    @Scheduled(cron = "0 2 * * * *")
    public void aggregateOneHour() {
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        OffsetDateTime start = end.minusHours(1);

        try {
            repo.aggregateOneHourWindow(start.minusHours(1), start);
        } catch (Exception ex) {
            log.warn("Late-event 1h recomputation failed: {}", ex.getMessage());
        }

        int rows = repo.aggregateOneHourWindow(start, end);
        log.info("Aggregated 1h window {} → {}: {} rows", start, end, rows);
    }
}
