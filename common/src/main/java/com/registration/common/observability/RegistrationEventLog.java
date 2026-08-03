package com.registration.common.observability;

import com.registration.common.protocol.ClientId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Structured log lines for the REGISTER/RENEW/CANCEL flow (ADR-0013, ADR-0014, ADR-0018):
 * Client ID, transaction, and event type. Shared by Server and Client (moved here in
 * ADR-0018) so both emit identically-shaped events. Client ID, transaction, and event type
 * are carried as structured key-value attributes (queryable independently once shipped via
 * OTel), not just interpolated into the message — the message string is kept in the same
 * human-readable shape as before for the console.
 */
public final class RegistrationEventLog {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEventLog.class);
    private static final String MESSAGE = "[{}] {} {}";

    private RegistrationEventLog() {
    }

    public static void log(ClientId clientId, String transaction, String eventType, Level level) {
        atLevel(level)
                .addKeyValue("clientId", clientId.toString())
                .addKeyValue("transaction", transaction)
                .addKeyValue("eventType", eventType)
                .log(MESSAGE, clientId, transaction, eventType);
    }

    private static LoggingEventBuilder atLevel(Level level) {
        return switch (level) {
            case TRACE -> log.atTrace();
            case INFO -> log.atInfo();
            case WARN -> log.atWarn();
            case DEBUG -> log.atDebug();
        };
    }

    public enum Level {
        TRACE, INFO, WARN, DEBUG
    }
}
