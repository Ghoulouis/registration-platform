package com.registration.client.stats;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** Aggregate statistics across every Simulated Client in the run (grilled Q10). */
public final class Stats {

    private final Map<OperationType, OperationStats> byType = new EnumMap<>(OperationType.class);
    private final Instant startedAt = Instant.now();

    public Stats() {
        for (OperationType type : OperationType.values()) {
            byType.put(type, new OperationStats());
        }
    }

    public OperationStats forType(OperationType type) {
        return byType.get(type);
    }

    /** Successful REGISTER calls per second of wall-clock time since this Stats was created. */
    public double actualRegistrationRatePerSecond() {
        double elapsedSeconds = Duration.between(startedAt, Instant.now()).toMillis() / 1000.0;
        if (elapsedSeconds <= 0) {
            return 0.0;
        }
        return forType(OperationType.REGISTER).snapshot().successes() / elapsedSeconds;
    }
}
