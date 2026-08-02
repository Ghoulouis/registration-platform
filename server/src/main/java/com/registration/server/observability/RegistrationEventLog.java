package com.registration.server.observability;

import com.registration.common.protocol.ClientId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured log lines for the REGISTER/RENEW/CANCEL flow (ADR-0013, ADR-0014): Client ID,
 * transaction, event type, and — for terminal steps only — a result. Called directly from
 * {@code RegistrationService} at each decision point, since step-level granularity is only
 * visible from inside the flow, not by classifying a finished (request, response) pair from
 * outside it. Console only for now; not yet shipped anywhere queryable.
 */
public final class RegistrationEventLog {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEventLog.class);
    private static final String STEP_MESSAGE = "[{}] {} {}";
    private static final String OUTCOME_MESSAGE = "[{}] {} {} -> {}";

    private RegistrationEventLog() {
    }

    /** An intermediate, informational step — always DEBUG, no result (not a completed transaction). */
    public static void step(ClientId clientId, String transaction, String eventType) {
        log.debug(STEP_MESSAGE, clientId, transaction, eventType);
    }

    /**
     * A completed transaction, successfully or not. {@code result} is {@code SUCCESS} or
     * {@code REJECTED} — severity is carried by {@code level}, not a third result value.
     */
    public static void outcome(ClientId clientId, String transaction, String eventType, String result, Level level) {
        switch (level) {
            case INFO -> log.info(OUTCOME_MESSAGE, clientId, transaction, eventType, result);
            case WARN -> log.warn(OUTCOME_MESSAGE, clientId, transaction, eventType, result);
            case DEBUG -> log.debug(OUTCOME_MESSAGE, clientId, transaction, eventType, result);
        }
    }

    public enum Level {
        INFO, WARN, DEBUG
    }
}
