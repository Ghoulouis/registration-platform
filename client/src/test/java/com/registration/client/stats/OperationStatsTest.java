package com.registration.client.stats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperationStatsTest {

    @Test
    void tracksAttemptsRetriesTimeoutsAndOutcomes() {
        OperationStats stats = new OperationStats();

        stats.recordAttempt(false);
        stats.recordTimeout();
        stats.recordAttempt(true);
        stats.recordResponseTime(10);
        stats.recordOutcome(true);

        OperationStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.totalAttempts()).isEqualTo(2);
        assertThat(snapshot.retryAttempts()).isEqualTo(1);
        assertThat(snapshot.timeouts()).isEqualTo(1);
        assertThat(snapshot.successes()).isEqualTo(1);
        assertThat(snapshot.failures()).isEqualTo(0);
    }

    @Test
    void computesAverageMinAndMaxResponseTime() {
        OperationStats stats = new OperationStats();

        stats.recordResponseTime(10);
        stats.recordResponseTime(20);
        stats.recordResponseTime(30);

        OperationStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.averageResponseTimeMillis()).isEqualTo(20.0);
        assertThat(snapshot.minResponseTimeMillis()).isEqualTo(10);
        assertThat(snapshot.maxResponseTimeMillis()).isEqualTo(30);
    }

    @Test
    void responseTimeStatsAreZeroWhenNoneRecorded() {
        OperationStats.Snapshot snapshot = new OperationStats().snapshot();

        assertThat(snapshot.averageResponseTimeMillis()).isEqualTo(0.0);
        assertThat(snapshot.minResponseTimeMillis()).isEqualTo(0);
        assertThat(snapshot.maxResponseTimeMillis()).isEqualTo(0);
    }
}
