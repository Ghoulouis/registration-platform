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
        @DefaultValue("60") int assumedValidityPeriodSeconds,
        @DefaultValue("60") int renewalWindowMinPercent,
        @DefaultValue("90") int renewalWindowMaxPercent,
        @DefaultValue("2000") long timeoutMillis,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("200") long retryBaseDelayMillis,
        @DefaultValue("") String authToken) {

    public ClientProperties {
        if (simulatedClients < 1) {
            throw new IllegalArgumentException("client.simulated-clients must be at least 1: " + simulatedClients);
        }
        if (registerRatePerSecond <= 0) {
            throw new IllegalArgumentException(
                    "client.register-rate-per-second must be positive: " + registerRatePerSecond);
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
