package com.registration.server.domain;

import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.Challenge;
import com.registration.common.protocol.ChallengeResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.store.InMemoryChallengeStore;
import com.registration.server.store.InMemoryRegistrationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationServiceTest {

    private static final int VALIDITY_PERIOD_SECONDS = 60;

    // Demo Shared Signing Key (ADR-0009); matches RegistrationProperties' default authPublicKey.
    private static final String PRIVATE_SEED_B64 = "pU55QBNBWdgYnCyCaZsfU3jImcyqZKGmSv3Nb+YEEbM=";
    private static final String PUBLIC_KEY_B64 = "OyqZa3x46M9IqazQAsypDYZr244z47nMSQVPmoK7Kcw=";

    private RegistrationService service;
    private PrivateKey signingKey;

    @BeforeEach
    void setUp() {
        RegistrationProperties properties =
                new RegistrationProperties(0, VALIDITY_PERIOD_SECONDS, 1000, 30, PUBLIC_KEY_B64);
        service = new RegistrationService(new InMemoryRegistrationStore(), new InMemoryChallengeStore(), properties);
        signingKey = Ed25519.parsePrivateKey(PRIVATE_SEED_B64);
    }

    @Test
    void registerNewClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(register(clientId)).isEqualTo(RegisterResponse.success(VALIDITY_PERIOD_SECONDS));
    }

    @Test
    void initialRegisterRequestReturnsAChallenge() {
        ClientId clientId = ClientId.parse("123456789012");

        RegisterResponse response = (RegisterResponse) service.handle(RegisterRequest.initial(clientId));

        assertThat(response.status()).isEqualTo(StatusCode.CHALLENGE);
        assertThat(response.challenge()).isNotNull();
    }

    @Test
    void wrongSignatureIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(RegisterRequest.initial(clientId));
        ChallengeResponse bogus = ChallengeResponse.of(new byte[ChallengeResponse.LENGTH]);

        assertThat(service.handle(RegisterRequest.withChallengeResponse(clientId, bogus)))
                .isEqualTo(RegisterResponse.challengeRejected());
    }

    @Test
    void challengeCannotBeReusedAfterAFailedAttempt() {
        ClientId clientId = ClientId.parse("123456789012");
        RegisterResponse challengeResponse = (RegisterResponse) service.handle(RegisterRequest.initial(clientId));
        Challenge challenge = challengeResponse.challenge();
        ChallengeResponse bogus = ChallengeResponse.of(new byte[ChallengeResponse.LENGTH]);
        service.handle(RegisterRequest.withChallengeResponse(clientId, bogus)); // burns the Challenge

        ChallengeResponse validSignature = Ed25519.sign(signingKey, challenge);
        assertThat(service.handle(RegisterRequest.withChallengeResponse(clientId, validSignature)))
                .isEqualTo(RegisterResponse.challengeRejected());
    }

    @Test
    void registerTwiceIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");
        register(clientId);

        assertThat(service.handle(RegisterRequest.initial(clientId))).isEqualTo(RegisterResponse.alreadyRegistered());
    }

    @Test
    void renewRegisteredClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");
        register(clientId);

        assertThat(service.handle(new RenewRequest(clientId)))
                .isEqualTo(new RenewResponse(StatusCode.SUCCESS, VALIDITY_PERIOD_SECONDS));
    }

    @Test
    void renewUnregisteredClientIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(service.handle(new RenewRequest(clientId)))
                .isEqualTo(new RenewResponse(StatusCode.NOT_REGISTERED, 0));
    }

    @Test
    void cancelRegisteredClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");
        register(clientId);

        assertThat(service.handle(new CancelRequest(clientId))).isEqualTo(new CancelResponse(StatusCode.SUCCESS));
    }

    @Test
    void cancelUnregisteredClientIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(service.handle(new CancelRequest(clientId))).isEqualTo(new CancelResponse(StatusCode.NOT_REGISTERED));
    }

    @Test
    void cancelledClientCanRegisterAgain() {
        ClientId clientId = ClientId.parse("123456789012");
        register(clientId);
        service.handle(new CancelRequest(clientId));

        assertThat(register(clientId)).isEqualTo(RegisterResponse.success(VALIDITY_PERIOD_SECONDS));
    }

    private RegisterResponse register(ClientId clientId) {
        RegisterResponse challengeResponse = (RegisterResponse) service.handle(RegisterRequest.initial(clientId));
        ChallengeResponse signature = Ed25519.sign(signingKey, challengeResponse.challenge());
        return (RegisterResponse) service.handle(RegisterRequest.withChallengeResponse(clientId, signature));
    }
}
