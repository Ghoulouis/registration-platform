package com.registration.server.domain;

import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.Challenge;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.store.ChallengeStore;
import com.registration.server.store.RegistrationStore;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Duration;

/**
 * Applies REGISTER/RENEW/CANCEL symmetry (ADR-0003, ADR-0004), same as the Distributed
 * Server's equivalent — synchronous here since {@link RegistrationStore} has no I/O to await.
 * REGISTER is a two-step Challenge/Challenge Response exchange (ADR-0009), handled here as
 * one stateless call per step; the Challenge Store carries state between the two.
 */
@Service
public class RegistrationService {

    private final RegistrationStore store;
    private final ChallengeStore challengeStore;
    private final Duration validityPeriod;
    private final Duration challengeTtl;
    private final PublicKey authPublicKey;

    public RegistrationService(RegistrationStore store, ChallengeStore challengeStore, RegistrationProperties properties) {
        this.store = store;
        this.challengeStore = challengeStore;
        this.validityPeriod = Duration.ofSeconds(properties.validityPeriodSeconds());
        this.challengeTtl = Duration.ofSeconds(properties.challengeTtlSeconds());
        this.authPublicKey = Ed25519.parsePublicKey(properties.authPublicKey());
    }

    public ProtocolMessage handle(ProtocolMessage request) {
        return switch (request) {
            case RegisterRequest r -> register(r);
            case RenewRequest r -> renew(r);
            case CancelRequest r -> cancel(r);
            case RegisterResponse ignored -> throw unexpected(request);
            case RenewResponse ignored -> throw unexpected(request);
            case CancelResponse ignored -> throw unexpected(request);
        };
    }

    private RegisterResponse register(RegisterRequest request) {
        return request.hasChallengeResponse()
                ? verifyChallengeResponse(request)
                : issueChallenge(request);
    }

    private RegisterResponse issueChallenge(RegisterRequest request) {
        if (store.isRegistered(request.clientId())) {
            return RegisterResponse.alreadyRegistered();
        }
        Challenge challenge = challengeStore.issue(request.clientId(), challengeTtl);
        return RegisterResponse.challenge(challenge);
    }

    private RegisterResponse verifyChallengeResponse(RegisterRequest request) {
        Challenge challenge = challengeStore.consume(request.clientId());
        if (challenge == null || !Ed25519.verify(authPublicKey, challenge, request.challengeResponse())) {
            return RegisterResponse.challengeRejected();
        }
        boolean created = store.tryRegister(request.clientId(), validityPeriod);
        return created ? RegisterResponse.success((int) validityPeriod.toSeconds()) : RegisterResponse.alreadyRegistered();
    }

    private ProtocolMessage renew(RenewRequest request) {
        boolean renewed = store.renew(request.clientId(), validityPeriod);
        return renewed
                ? new RenewResponse(StatusCode.SUCCESS, (int) validityPeriod.toSeconds())
                : new RenewResponse(StatusCode.NOT_REGISTERED, 0);
    }

    private ProtocolMessage cancel(CancelRequest request) {
        boolean cancelled = store.cancel(request.clientId());
        return cancelled
                ? new CancelResponse(StatusCode.SUCCESS)
                : new CancelResponse(StatusCode.NOT_REGISTERED);
    }

    private static IllegalArgumentException unexpected(ProtocolMessage request) {
        return new IllegalArgumentException("Server does not accept " + request.type());
    }
}
