package com.registration.client.simulation;

import com.registration.client.stats.OperationStats;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records one timestamped snapshot of REGISTER/RENEW {@link Stats} per second throughout a
 * Benchmark Run ({@link #recordSnapshot}), then writes the full time series as a JSON array
 * once Benchmark Mode self-terminates ({@link #write}). REGISTER/RENEW only - CANCEL is
 * graceful-shutdown housekeeping (ADR-0004), not a Load Profile-driven operation this report
 * is meant to characterize. Counts (successes/failures/totals) are cumulative; response times
 * are windowed (since the last snapshot) with the whole-run cumulative figures carried
 * alongside them - see {@link #section}.
 */
final class BenchmarkReport {

    static final Path DEFAULT_PATH = Path.of("benchmark-report.json");

    private final List<String> snapshots = new CopyOnWriteArrayList<>();

    /** Thread-safe: intended to be called once a second from a dedicated scheduler thread. */
    void recordSnapshot(Stats stats) {
        snapshots.add(toJsonSnapshot(Instant.now(), stats));
    }

    void write() {
        write(DEFAULT_PATH);
    }

    void write(Path path) {
        String json = "[\n" + String.join(",\n", snapshots) + "\n]\n";
        try {
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write benchmark report to " + path, e);
        }
    }

    private static String toJsonSnapshot(Instant timestamp, Stats stats) {
        OperationStats registerStats = stats.forType(OperationType.REGISTER);
        OperationStats renewStats = stats.forType(OperationType.RENEW);

        // Counts stay cumulative (whole run so far); response times are windowed (since the
        // last snapshot) - a cumulative avg/min/max flattens out and stops being informative
        // once the sample count grows large, hiding exactly the "is it degrading over time"
        // signal a time series exists to show (grilled).
        OperationStats.Snapshot register = registerStats.snapshot();
        OperationStats.Snapshot renew = renewStats.snapshot();
        OperationStats.ResponseTimeWindow registerWindow = registerStats.snapshotWindowAndReset();
        OperationStats.ResponseTimeWindow renewWindow = renewStats.snapshotWindowAndReset();

        long totalRequests = register.totalAttempts() + renew.totalAttempts();
        long totalTimeouts = register.timeouts() + renew.timeouts();
        long totalRetries = register.retryAttempts() + renew.retryAttempts();

        return """
                  {
                    "timestamp": "%s",
                    "totalRequests": %d,
                    "totalTimeouts": %d,
                    "totalRetries": %d,
                    "register": %s,
                    "renew": %s
                  }""".formatted(
                timestamp, totalRequests, totalTimeouts, totalRetries,
                section(register, registerWindow), section(renew, renewWindow));
    }

    /**
     * averageResponseTimeMillis/min/max are windowed (since the last snapshot) - the right
     * shape for charting response times over time. cumulativeAverageResponseTimeMillis/min/max
     * carry the whole-run figures alongside them, so the array's last entry alone is still
     * enough to render a final run-wide summary without recomputing anything from the window
     * values (which can't be recombined into an accurate whole-run min/max/average anyway).
     */
    private static String section(OperationStats.Snapshot snapshot, OperationStats.ResponseTimeWindow window) {
        return """
                {
                      "successes": %d,
                      "failures": %d,
                      "averageResponseTimeMillis": %s,
                      "minResponseTimeMillis": %d,
                      "maxResponseTimeMillis": %d,
                      "cumulativeAverageResponseTimeMillis": %s,
                      "cumulativeMinResponseTimeMillis": %d,
                      "cumulativeMaxResponseTimeMillis": %d
                    }""".formatted(
                snapshot.successes(),
                snapshot.failures(),
                String.format(Locale.ROOT, "%.2f", window.averageResponseTimeMillis()),
                window.minResponseTimeMillis(),
                window.maxResponseTimeMillis(),
                String.format(Locale.ROOT, "%.2f", snapshot.averageResponseTimeMillis()),
                snapshot.minResponseTimeMillis(),
                snapshot.maxResponseTimeMillis());
    }
}
