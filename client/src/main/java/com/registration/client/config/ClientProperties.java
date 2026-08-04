package com.registration.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Client Simulator configuration, bound from CLI flags (e.g. {@code --client.mode=benchmark}).
 * See CONTEXT.md's "Client Simulator" terms and ADR-0004/0005/0006.
 */
@ConfigurationProperties(prefix = "client")
public record ClientProperties(
        @DefaultValue("localhost") String serverHost,
        @DefaultValue("9000") int serverPort,
        @DefaultValue("normal") Mode mode,
        @DefaultValue("1") int simulatedClients,
        @DefaultValue("10") double registerRatePerSecond,
        @DefaultValue("60") int benchmarkDurationSeconds,
        @DefaultValue("60") int assumedValidityPeriodSeconds,
        @DefaultValue("60") int renewalWindowMinPercent,
        @DefaultValue("90") int renewalWindowMaxPercent,
        @DefaultValue("2000") long timeoutMillis,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("200") long retryBaseDelayMillis,
        // Demo Shared Signing Key private half (ADR-0009) - matches the Server's default
        // authPublicKey. Not for production use; override both to use a real keypair.
        @DefaultValue("pU55QBNBWdgYnCyCaZsfU3jImcyqZKGmSv3Nb+YEEbM=") String authPrivateKey,
        // Whether a Simulated Client sends its voluntary CANCEL (ADR-0004) on shutdown.
        // Defaults to true (the documented Client Simulator lifecycle, CONTEXT.md); set false
        // to leave Registrations behind on exit instead, e.g. to test Expiration (ADR-0007).
        @DefaultValue("true") boolean cancelOnExit) {

    public ClientProperties {
        if (simulatedClients < 1) {
            throw new IllegalArgumentException("client.simulated-clients must be at least 1: " + simulatedClients);
        }
        if (registerRatePerSecond <= 0) {
            throw new IllegalArgumentException(
                    "client.register-rate-per-second must be positive: " + registerRatePerSecond);
        }
        if (benchmarkDurationSeconds < 1) {
            throw new IllegalArgumentException(
                    "client.benchmark-duration-seconds must be at least 1: " + benchmarkDurationSeconds);
        }
        if (renewalWindowMinPercent < 0 || renewalWindowMaxPercent > 99
                || renewalWindowMinPercent >= renewalWindowMaxPercent) {
            throw new IllegalArgumentException(
                    "client.renewal-window-min-percent must be < client.renewal-window-max-percent, "
                            + "both within [0, 99]: " + renewalWindowMinPercent + ", " + renewalWindowMaxPercent);
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("client.timeout-millis must be positive: " + timeoutMillis);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("client.max-retries must not be negative: " + maxRetries);
        }
        if (retryBaseDelayMillis < 0) {
            throw new IllegalArgumentException("client.retry-base-delay-millis must not be negative: "
                    + retryBaseDelayMillis);
        }
    }

    /** Normal Mode runs one Simulated Client indefinitely; Benchmark Mode runs many, rate-limited. */
    public enum Mode {
        NORMAL, BENCHMARK
    }
}
