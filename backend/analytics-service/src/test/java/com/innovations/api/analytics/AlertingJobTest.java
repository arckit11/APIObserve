package com.innovations.api.analytics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;

class AlertingJobTest {

    private JdbcTemplate jdbc;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, Object> kafka;
    private AlertingJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        kafka = mock(KafkaTemplate.class);
        job = new AlertingJob(jdbc, kafka, "alerts");
    }

    @Test
    void evaluateQueriesTrafficAndClosesAlertsOnlyOnConfirmedRecovery() {
        when(jdbc.query(any(String.class), any(RowMapper.class), any())).thenReturn(Collections.emptyList());

        job.evaluate();

        // Verify the alert query requires request_count > 0
        verify(jdbc).query(contains("request_count > 0"), any(RowMapper.class), any());

        // Verify that alert close query requires positive evidence (request_count > 0 AND p99 <= threshold AND error_rate <= threshold)
        verify(jdbc).update(contains("request_count > 0"), any(), eq(2000.0), eq(0.10));
    }
}
