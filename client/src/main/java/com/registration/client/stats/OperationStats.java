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
}
