package com.registration.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.registration.common.protocol.ClientId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies RegistrationEventLog's own contract (ADR-0018): Client ID, transaction, and event
 * type are carried as structured key-value attributes (queryable independently once shipped
 * via OTel), and the rendered message stays in its historical human-readable shape for the
 * console appender.
 */
class RegistrationEventLogTest {

    private static final ClientId CLIENT_ID = ClientId.parse("123456789012");

    private Logger eventLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        eventLogger = (Logger) LoggerFactory.getLogger(RegistrationEventLog.class);
        eventLogger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        eventLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        eventLogger.detachAppender(appender);
    }

    @Test
    void logCarriesClientIdTransactionAndEventTypeAsAttributes() {
        RegistrationEventLog.log(CLIENT_ID, "REGISTER", "nonce_requested", RegistrationEventLog.Level.DEBUG);

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(keyValue(event, "clientId")).isEqualTo(CLIENT_ID.toString());
        assertThat(keyValue(event, "transaction")).isEqualTo("REGISTER");
        assertThat(keyValue(event, "eventType")).isEqualTo("nonce_requested");
        assertThat(event.getFormattedMessage()).isEqualTo("[123456789012] REGISTER nonce_requested");
    }

    @Test
    void logAtInfoUsesInfoSeverity() {
        RegistrationEventLog.log(CLIENT_ID, "REGISTER", "auth_success", RegistrationEventLog.Level.INFO);

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void logAtWarnUsesWarnSeverity() {
        RegistrationEventLog.log(CLIENT_ID, "RENEW", "invalid_signature", RegistrationEventLog.Level.WARN);

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void logAtTraceUsesTraceSeverity() {
        RegistrationEventLog.log(CLIENT_ID, "REGISTER", "requesting_nonce", RegistrationEventLog.Level.TRACE);

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.TRACE);
    }

    private ILoggingEvent onlyEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    private static String keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(kv -> kv.key.equals(key))
                .map(kv -> (String) kv.value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No \"" + key + "\" key-value pair on: " + event));
    }
}
