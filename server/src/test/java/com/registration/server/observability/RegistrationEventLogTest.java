package com.registration.server.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.NonceSignature;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.TraceContext;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.domain.RegistrationService;
import com.registration.server.store.InMemoryRegistrationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.security.PrivateKey;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the actual step/outcome event sequence RegistrationService emits (ADR-0014) by
 * capturing RegistrationEventLog's real output, rather than testing classification logic in
 * isolation - there's no such logic left to isolate, it's inline at each decision point now.
 */
class RegistrationEventLogTest {

    private static final int VALIDITY_PERIOD_SECONDS = 60;

    // Demo Shared Signing Key (ADR-0009); matches RegistrationProperties' default authPublicKey.
    private static final String PRIVATE_SEED_B64 = "pU55QBNBWdgYnCyCaZsfU3jImcyqZKGmSv3Nb+YEEbM=";
    private static final String PUBLIC_KEY_B64 = "OyqZa3x46M9IqazQAsypDYZr244z47nMSQVPmoK7Kcw=";

    private RegistrationService service;
    private PrivateKey signingKey;
    private ListAppender<ILoggingEvent> appender;
    private Logger eventLogger;

    @BeforeEach
    void setUp() {
        RegistrationProperties properties =
                new RegistrationProperties(0, VALIDITY_PERIOD_SECONDS, 1000, 30, PUBLIC_KEY_B64);
        service = new RegistrationService(new InMemoryRegistrationStore(), properties);
        signingKey = Ed25519.parsePrivateKey(PRIVATE_SEED_B64);

        eventLogger = (Logger) LoggerFactory.getLogger(RegistrationEventLog.class);
        eventLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        eventLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        eventLogger.detachAppender(appender);
    }

    @Test
    void registerSuccessLogsTheFullStepSequence() {
        ClientId clientId = ClientId.parse("123456789012");

        RegisterResponse challenge = (RegisterResponse) service.handle(RegisterRequest.initial(clientId, TraceContext.newTrace()));
        NonceSignature signature = Ed25519.sign(signingKey, challenge.nonce());
        service.handle(RegisterRequest.withNonceSignature(clientId, TraceContext.newTrace(), signature));

        assertThat(eventTypes()).containsExactly(
                "nonce_requested", "nonce_issued", "auth_data_received", "auth_success", "db_updated");
    }

    @Test
    void registerWithWrongSignatureLogsInvalidSignature() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(RegisterRequest.initial(clientId, TraceContext.newTrace()));
        NonceSignature bogus = NonceSignature.of(new byte[NonceSignature.LENGTH]);

        service.handle(RegisterRequest.withNonceSignature(clientId, TraceContext.newTrace(), bogus));

        assertThat(eventTypes()).containsExactly(
                "nonce_requested", "nonce_issued", "auth_data_received", "invalid_signature");
    }

    @Test
    void renewSuccessLogsAuthSuccessThenDbUpdated() {
        ClientId clientId = ClientId.parse("123456789012");
        var nonce = register(clientId);
        appender.list.clear();

        NonceSignature signature = Ed25519.sign(signingKey, nonce);
        service.handle(new RenewRequest(clientId, TraceContext.newTrace(), signature));

        assertThat(eventTypes()).containsExactly("auth_data_received", "auth_success", "db_updated");
    }

    @Test
    void renewWithWrongNonceLogsInvalidSignature() {
        ClientId clientId = ClientId.parse("123456789012");
        register(clientId);
        appender.list.clear();

        NonceSignature signature = Ed25519.sign(signingKey, com.registration.common.protocol.Nonce.random());
        RenewResponse response = (RenewResponse) service.handle(new RenewRequest(clientId, TraceContext.newTrace(), signature));

        assertThat(response.status()).isEqualTo(com.registration.common.protocol.StatusCode.INVALID_TOKEN);
        assertThat(eventTypes()).containsExactly("auth_data_received", "invalid_signature");
    }

    @Test
    void cancelSuccessLogsAuthSuccessThenDbUpdated() {
        ClientId clientId = ClientId.parse("123456789012");
        var nonce = register(clientId);
        appender.list.clear();

        NonceSignature signature = Ed25519.sign(signingKey, nonce);
        service.handle(new CancelRequest(clientId, TraceContext.newTrace(), signature));

        assertThat(eventTypes()).containsExactly("auth_data_received", "auth_success", "db_updated");
    }

    private com.registration.common.protocol.Nonce register(ClientId clientId) {
        RegisterResponse challenge = (RegisterResponse) service.handle(RegisterRequest.initial(clientId, TraceContext.newTrace()));
        NonceSignature signature = Ed25519.sign(signingKey, challenge.nonce());
        RegisterResponse response =
                (RegisterResponse) service.handle(RegisterRequest.withNonceSignature(clientId, TraceContext.newTrace(), signature));
        return response.nonce();
    }

    private List<String> eventTypes() {
        return appender.list.stream().map(event -> (String) event.getArgumentArray()[2]).toList();
    }
}
