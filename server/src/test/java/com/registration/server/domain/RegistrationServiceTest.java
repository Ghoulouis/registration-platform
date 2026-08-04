package com.registration.server.domain;

import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.NonceSignature;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.common.protocol.TraceContext;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.store.InMemoryRegistrationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationServiceTest {

    private static final int VALIDITY_PERIOD_SECONDS = 60;

    // Demo Shared Signing Key (ADR-0009); matches RegistrationProperties' default authPublicKey.
    private static final String PRIVATE_SEED_B64 = "pU55QBNBWdgYnCyCaZsfU3jImcyqZKGmSv3Nb+YEEbM=";
    private static final String PUBLIC_KEY_B64 = "OyqZa3x46M9IqazQAsypDYZr244z47nMSQVPmoK7Kcw=";

    private InMemoryRegistrationStore store;
    private RegistrationService service;
    private PrivateKey signingKey;

    @BeforeEach
    void setUp() {
        RegistrationProperties properties =
                new RegistrationProperties(0, VALIDITY_PERIOD_SECONDS, 1000, 30, PUBLIC_KEY_B64);
        store = new InMemoryRegistrationStore(100, 512);
        service = new RegistrationService(store, properties);
        signingKey = Ed25519.parsePrivateKey(PRIVATE_SEED_B64);
    }

    @AfterEach
    void tearDown() {
        store.shutdown();
    }

    @Test
    void registerNewClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");

        RegisterResponse response = register(clientId);

        assertThat(response.status()).isEqualTo(StatusCode.SUCCESS);
        assertThat(response.validityPeriodSeconds()).isEqualTo(VALIDITY_PERIOD_SECONDS);
        assertThat(response.nonce()).isNotNull();
    }

    @Test
    void initialRegisterRequestReturnsAChallenge() {
        ClientId clientId = ClientId.parse("123456789012");

        RegisterResponse response = (RegisterResponse) service.handle(RegisterRequest.initial(clientId, TraceContext.newTrace()));

        assertThat(response.status()).isEqualTo(StatusCode.CHALLENGE);
        assertThat(response.nonce()).isNotNull();
    }

    @Test
    void wrongSignatureIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(RegisterRequest.initial(clientId, TraceContext.newTrace()));
        NonceSignature bogus = NonceSignature.of(new byte[NonceSignature.LENGTH]);

        assertThat(service.handle(RegisterRequest.withNonceSignature(clientId, TraceContext.newTrace(), bogus)))
                .isEqualTo(RegisterResponse.challengeRejected());
    }

    @Test
    void pendingNonceCannotBeReusedAfterAFailedAttempt() {
        ClientId clientId = ClientId.parse("123456789012");
        RegisterResponse challengeResponse = (RegisterResponse) service.handle(RegisterRequest.initial(clientId, TraceContext.newTrace()));
        Nonce pendingNonce = challengeResponse.nonce();
        NonceSignature bogus = NonceSignature.of(new byte[NonceSignature.LENGTH]);
        service.handle(RegisterRequest.withNonceSignature(clientId, TraceContext.newTrace(), bogus)); // wrong signature burns the pending Nonce (ADR-0009)

        NonceSignature validSignature = Ed25519.sign(signingKey, pendingNonce);
        assertThat(service.handle(RegisterRequest.withNonceSignature(clientId, TraceContext.newTrace(), validSignature)))
                .isEqualTo(RegisterResponse.challengeRejected());
    }

    @Test
    void registerTwiceIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");
        RegisterResponse first = register(clientId);

        RegisterResponse second = (RegisterResponse) service.handle(RegisterRequest.initial(clientId, TraceContext.newTrace()));

        assertThat(second.status()).isEqualTo(StatusCode.ALREADY_REGISTERED);
        assertThat(second.nonce()).isEqualTo(first.nonce());
    }

    @Test
    void renewRegisteredClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");
        Nonce nonce = register(clientId).nonce();

        RenewResponse response = renew(clientId, nonce);

        assertThat(response.status()).isEqualTo(StatusCode.SUCCESS);
        assertThat(response.validityPeriodSeconds()).isEqualTo(VALIDITY_PERIOD_SECONDS);
        assertThat(response.nonce()).isNotEqualTo(nonce);
    }

    @Test
    void renewUnregisteredClientIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(renew(clientId, Nonce.random())).isEqualTo(RenewResponse.notRegistered());
    }

    @Test
    void renewWithWrongNonceReturnsInvalidToken() {
        ClientId clientId = ClientId.parse("123456789012");
        Nonce nonce = register(clientId).nonce();

        RenewResponse response = renew(clientId, Nonce.random());

        assertThat(response.status()).isEqualTo(StatusCode.INVALID_TOKEN);
        assertThat(response.nonce()).isEqualTo(nonce);
    }

    @Test
    void renewWithThePreviousNonceIsToleratedAsARetryAndDoesNotRotateAgain() {
        ClientId clientId = ClientId.parse("123456789012");
        Nonce initialNonce = register(clientId).nonce();
        Nonce rotatedNonce = renew(clientId, initialNonce).nonce();

        // Simulates a retry: the Client never learned rotatedNonce because the first
        // response was lost, so it signs the Nonce it still has (ADR-0010's grace window).
        RenewResponse graceResponse = renew(clientId, initialNonce);

        assertThat(graceResponse.status()).isEqualTo(StatusCode.SUCCESS);
        assertThat(graceResponse.nonce()).isEqualTo(rotatedNonce);
        // No rotation happens on this retry, so validityPeriodSeconds must reflect the actual
        // remaining time from the earlier rotation, not a fresh full period - some time has
        // necessarily elapsed since that rotation, so this is strictly less, never equal.
        assertThat(graceResponse.validityPeriodSeconds()).isLessThan(VALIDITY_PERIOD_SECONDS);

        // A real Renewal with the actual current Nonce still works afterward.
        RenewResponse realResponse = renew(clientId, rotatedNonce);
        assertThat(realResponse.status()).isEqualTo(StatusCode.SUCCESS);
    }

    @Test
    void cancelRegisteredClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");
        Nonce nonce = register(clientId).nonce();

        assertThat(cancel(clientId, nonce)).isEqualTo(CancelResponse.success());
    }

    @Test
    void cancelUnregisteredClientIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(cancel(clientId, Nonce.random())).isEqualTo(CancelResponse.notRegistered());
    }

    @Test
    void cancelWithWrongNonceReturnsInvalidToken() {
        ClientId clientId = ClientId.parse("123456789012");
        Nonce nonce = register(clientId).nonce();

        CancelResponse response = cancel(clientId, Nonce.random());

        assertThat(response.status()).isEqualTo(StatusCode.INVALID_TOKEN);
        assertThat(response.nonce()).isEqualTo(nonce);
    }

    @Test
    void cancelledClientCanRegisterAgain() {
        ClientId clientId = ClientId.parse("123456789012");
        Nonce nonce = register(clientId).nonce();
        cancel(clientId, nonce);

        RegisterResponse response = register(clientId);

        assertThat(response.status()).isEqualTo(StatusCode.SUCCESS);
    }

    private RegisterResponse register(ClientId clientId) {
        RegisterResponse challengeResponse = (RegisterResponse) service.handle(RegisterRequest.initial(clientId, TraceContext.newTrace()));
        NonceSignature signature = Ed25519.sign(signingKey, challengeResponse.nonce());
        return (RegisterResponse) service.handle(RegisterRequest.withNonceSignature(clientId, TraceContext.newTrace(), signature));
    }

    private RenewResponse renew(ClientId clientId, Nonce nonceToSign) {
        NonceSignature signature = Ed25519.sign(signingKey, nonceToSign);
        return (RenewResponse) service.handle(new RenewRequest(clientId, TraceContext.newTrace(), signature));
    }

    private CancelResponse cancel(ClientId clientId, Nonce nonceToSign) {
        NonceSignature signature = Ed25519.sign(signingKey, nonceToSign);
        return (CancelResponse) service.handle(new CancelRequest(clientId, TraceContext.newTrace(), signature));
    }
}
