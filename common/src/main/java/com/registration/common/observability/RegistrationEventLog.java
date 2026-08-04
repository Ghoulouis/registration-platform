package com.registration.common.observability;

import com.registration.common.protocol.ClientId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.Objects;

/**
 * Structured log lines for the REGISTER/RENEW/CANCEL flow (ADR-0013, ADR-0014, ADR-0018).
 * Shared by Server and Client so both emit identically-shaped structured events via SLF4J Fluent API.
 */
public final class RegistrationEventLog {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEventLog.class);

    // Message templates được định danh rõ ràng theo tham số
    private static final String MSG_2_ARGS = "{} {}";
    private static final String MSG_3_ARGS = "[{}] {} {}";
    private static final String MSG_4_ARGS = "[{}] {} {} {}";

    private RegistrationEventLog() {
        // Prevent instantiation
    }

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    // --- 1. Log cơ bản (Không có ClientId) ---

    public static void log(String eventType, Level level) {
        log(null, eventType, level, null);
    }
    
    public static void log(String transaction, String eventType, Level level) {
        log(transaction, eventType, level, null);
    }

    public static void log(String transaction, String eventType, Level level, Throwable throwable) {
        builder(level, throwable)
                .addKeyValue("transaction", transaction)
                .addKeyValue("eventType", eventType)
                .log(MSG_2_ARGS, transaction, eventType);
    }

    // --- 2. Log có ClientId ---

    public static void log(ClientId clientId, String transaction, String eventType, Level level) {
        log(clientId, transaction, eventType, level, null);
    }

    public static void log(ClientId clientId, String transaction, String eventType, Level level, Throwable throwable) {
        String clientStr = String.valueOf(clientId);
        builder(level, throwable)
                .addKeyValue("clientId", clientStr)
                .addKeyValue("transaction", transaction)
                .addKeyValue("eventType", eventType)
                .log(MSG_3_ARGS, clientStr, transaction, eventType);
    }

    // --- 3. Log Data/Metric có ClientId ---

    public static void logData(ClientId clientId, String transaction, String eventType, Level level, double value) {
        String clientStr = String.valueOf(clientId);
        builder(level, null)
                .addKeyValue("clientId", clientStr)
                .addKeyValue("transaction", transaction)
                .addKeyValue("eventType", eventType)
                .addKeyValue("value", value)
                .log(MSG_4_ARGS, clientStr, transaction, eventType, value);
    }

    // --- 4. Log Data/Metric dạng Aggregate (Không có ClientId) ---

    public static void logData(String transaction, String eventType, Level level, double value) {
        builder(level, null)
                .addKeyValue("transaction", transaction)
                .addKeyValue("eventType", eventType)
                .addKeyValue("value", value)
                .log(MSG_3_ARGS, transaction, eventType, value);
    }

    // --- Helper Methods ---

    private static LoggingEventBuilder builder(Level level, Throwable throwable) {
        Level targetLevel = Objects.requireNonNullElse(level, Level.INFO);
        LoggingEventBuilder builder = switch (targetLevel) {
            case TRACE -> log.atTrace();
            case DEBUG -> log.atDebug();
            case INFO  -> log.atInfo();
            case WARN  -> log.atWarn();
            case ERROR -> log.atError();
        };

        if (throwable != null) {
            builder.setCause(throwable);
        }

        return builder;
    }
}