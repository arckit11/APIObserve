package com.innovations.api.analytics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class WindowingJobTest {

    private MetricsRepository repo;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, Object> kafka;
    private WindowingJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(MetricsRepository.class);
        kafka = mock(KafkaTemplate.class);
        job = new WindowingJob(repo, kafka, "metrics");
    }

    @Test
    void aggregateMinutePerformsCurrentAndLateEventRecomputation() {
        when(repo.aggregateOneMinuteWindow(any(), any())).thenReturn(5);
        when(repo.latestPerService()).thenReturn(Collections.emptyList());

        job.aggregateMinute();

        // Must run twice: once for [T-2m, T-1m) late event recompute, and once for [T-1m, T)
        verify(repo, times(2)).aggregateOneMinuteWindow(any(), any());
    }

    @Test
    void aggregateFiveMinutesPerformsCurrentAndLateRecomputation() {
        when(repo.aggregateFiveMinuteWindow(any(), any())).thenReturn(5);

        job.aggregateFiveMinutes();

        verify(repo, times(2)).aggregateFiveMinuteWindow(any(), any());
    }

    @Test
    void aggregateOneHourPerformsCurrentAndLateRecomputation() {
        when(repo.aggregateOneHourWindow(any(), any())).thenReturn(5);

        job.aggregateOneHour();

        verify(repo, times(2)).aggregateOneHourWindow(any(), any());
    }
}
