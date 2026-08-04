package com.registration.client.stats;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe counters for one {@link OperationType}, written concurrently from many
 * Simulated Clients' virtual threads.
 */
public final class OperationStats {

    private final LongAdder totalAttempts = new LongAdder();
    private final LongAdder retryAttempts = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder responseTimeCount = new LongAdder();
    private final LongAdder responseTimeSumMillis = new LongAdder();
    private final LongAccumulator responseTimeMinMillis = new LongAccumulator(Math::min, Long.MAX_VALUE);
    private final LongAccumulator responseTimeMaxMillis = new LongAccumulator(Math::max, Long.MIN_VALUE);

    // Same shape as the cumulative fields above, but reset on every snapshotWindowAndReset()
    // call (grilled) - lets a time series show how response times are behaving right now
    // instead of a whole-run cumulative figure that flattens out and stops being informative
    // once the sample count grows large.
    private final LongAdder windowResponseTimeCount = new LongAdder();
    private final LongAdder windowResponseTimeSumMillis = new LongAdder();
    private final LongAccumulator windowResponseTimeMinMillis = new LongAccumulator(Math::min, Long.MAX_VALUE);
    private final LongAccumulator windowResponseTimeMaxMillis = new LongAccumulator(Math::max, Long.MIN_VALUE);

    /** Call once per physical send, including retries. */
    public void recordAttempt(boolean isRetry) {
        totalAttempts.increment();
        if (isRetry) {
            retryAttempts.increment();
        }
    }

    /** Call once per physical attempt that timed out. */
    public void recordTimeout() {
        timeouts.increment();
    }

    /** Call once per physical attempt that received a well-formed response, whatever its status. */
    public void recordResponseTime(long millis) {
        responseTimeCount.increment();
        responseTimeSumMillis.add(millis);
        responseTimeMinMillis.accumulate(millis);
        responseTimeMaxMillis.accumulate(millis);

        windowResponseTimeCount.increment();
        windowResponseTimeSumMillis.add(millis);
        windowResponseTimeMinMillis.accumulate(millis);
        windowResponseTimeMaxMillis.accumulate(millis);
    }

    /** Call exactly once per logical call, after retries are exhausted or a final outcome is known. */
    public void recordOutcome(boolean success) {
        if (success) {
            successes.increment();
        } else {
            failures.increment();
        }
    }

    public Snapshot snapshot() {
        long count = responseTimeCount.sum();
        double average = count == 0 ? 0.0 : (double) responseTimeSumMillis.sum() / count;
        long min = count == 0 ? 0 : responseTimeMinMillis.get();
        long max = count == 0 ? 0 : responseTimeMaxMillis.get();
        return new Snapshot(
                totalAttempts.sum(),
                retryAttempts.sum(),
                timeouts.sum(),
                successes.sum(),
                failures.sum(),
                average,
                min,
                max);
    }

    /**
     * Response-time min/avg/max since the last call to this method (or since construction,
     * for the first call) - resets the window on every call, so callers must be the sole
     * consumer of it (e.g. one benchmark-report sampler tick). Counts/successes/failures have
     * no windowed equivalent; they stay cumulative - see {@link #snapshot()}.
     */
    public ResponseTimeWindow snapshotWindowAndReset() {
        long count = windowResponseTimeCount.sumThenReset();
        long sum = windowResponseTimeSumMillis.sumThenReset();
        double average = count == 0 ? 0.0 : (double) sum / count;
        long min = count == 0 ? 0 : windowResponseTimeMinMillis.getThenReset();
        long max = count == 0 ? 0 : windowResponseTimeMaxMillis.getThenReset();
        return new ResponseTimeWindow(average, min, max);
    }

    public record Snapshot(
            long totalAttempts,
            long retryAttempts,
            long timeouts,
            long successes,
            long failures,
            double averageResponseTimeMillis,
            long minResponseTimeMillis,
            long maxResponseTimeMillis) {
    }

    public record ResponseTimeWindow(
            double averageResponseTimeMillis,
            long minResponseTimeMillis,
            long maxResponseTimeMillis) {
    }
}
