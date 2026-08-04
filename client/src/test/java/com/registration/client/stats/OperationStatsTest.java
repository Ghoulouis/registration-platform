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

    @Test
    void windowResetsAfterEachSnapshotButCumulativeSnapshotKeepsGrowing() {
        OperationStats stats = new OperationStats();

        stats.recordResponseTime(10);
        stats.recordResponseTime(30);
        OperationStats.ResponseTimeWindow firstWindow = stats.snapshotWindowAndReset();
        assertThat(firstWindow.averageResponseTimeMillis()).isEqualTo(20.0);
        assertThat(firstWindow.minResponseTimeMillis()).isEqualTo(10);
        assertThat(firstWindow.maxResponseTimeMillis()).isEqualTo(30);

        // Nothing recorded since the window reset above.
        OperationStats.ResponseTimeWindow emptyWindow = stats.snapshotWindowAndReset();
        assertThat(emptyWindow.averageResponseTimeMillis()).isEqualTo(0.0);
        assertThat(emptyWindow.minResponseTimeMillis()).isEqualTo(0);
        assertThat(emptyWindow.maxResponseTimeMillis()).isEqualTo(0);

        // A later, much larger value shows up in the next window alone...
        stats.recordResponseTime(500);
        OperationStats.ResponseTimeWindow thirdWindow = stats.snapshotWindowAndReset();
        assertThat(thirdWindow.minResponseTimeMillis()).isEqualTo(500);
        assertThat(thirdWindow.maxResponseTimeMillis()).isEqualTo(500);

        // ...but the cumulative snapshot still reflects the true whole-run min/max (10/500),
        // unaffected by any of the window resets above.
        OperationStats.Snapshot cumulative = stats.snapshot();
        assertThat(cumulative.minResponseTimeMillis()).isEqualTo(10);
        assertThat(cumulative.maxResponseTimeMillis()).isEqualTo(500);
        assertThat(cumulative.averageResponseTimeMillis()).isEqualTo((10.0 + 30.0 + 500.0) / 3);
    }
}
