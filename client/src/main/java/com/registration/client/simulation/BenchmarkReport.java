package com.registration.client.simulation;

import com.registration.client.stats.OperationStats;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes a Benchmark Run's final {@link Stats} as JSON once Benchmark Mode self-terminates.
 * REGISTER/RENEW only - CANCEL is graceful-shutdown housekeeping (ADR-0004), not a Load
 * Profile-driven operation this report is meant to characterize.
 */
final class BenchmarkReport {

    static final Path DEFAULT_PATH = Path.of("benchmark-report.json");

    private BenchmarkReport() {
    }

    static void write(Stats stats) {
        write(stats, DEFAULT_PATH);
    }

    static void write(Stats stats, Path path) {
        try {
            Files.writeString(path, toJson(stats), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write benchmark report to " + path, e);
        }
    }

    static String toJson(Stats stats) {
        OperationStats.Snapshot register = stats.forType(OperationType.REGISTER).snapshot();
        OperationStats.Snapshot renew = stats.forType(OperationType.RENEW).snapshot();

        long totalRequests = register.totalAttempts() + renew.totalAttempts();
        long totalTimeouts = register.timeouts() + renew.timeouts();
        long totalRetries = register.retryAttempts() + renew.retryAttempts();

        return """
                {
                  "totalRequests": %d,
                  "totalTimeouts": %d,
                  "totalRetries": %d,
                  "register": %s,
                  "renew": %s
                }
                """.formatted(totalRequests, totalTimeouts, totalRetries, section(register), section(renew));
    }

    private static String section(OperationStats.Snapshot snapshot) {
        return """
                {
                    "successes": %d,
                    "failures": %d,
                    "averageResponseTimeMillis": %s,
                    "minResponseTimeMillis": %d,
                    "maxResponseTimeMillis": %d
                  }""".formatted(
                snapshot.successes(),
                snapshot.failures(),
                String.format(Locale.ROOT, "%.2f", snapshot.averageResponseTimeMillis()),
                snapshot.minResponseTimeMillis(),
                snapshot.maxResponseTimeMillis());
    }
}
