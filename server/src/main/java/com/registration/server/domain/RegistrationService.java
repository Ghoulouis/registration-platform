package com.registration.server.domain;

import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.Challenge;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.store.ChallengeStore;
import com.registration.server.store.RegistrationStore;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Duration;

/**
 * Applies REGISTER/RENEW/CANCEL symmetry (ADR-0003, ADR-0004), same as the Distributed
 * Server's equivalent — synchronous here since {@link RegistrationStore} has no I/O to
 * await. REGISTER is a two-step Challenge/Challenge Response exchange (ADR-0009); RENEW and
 * CANCEL are each authenticated by a Nonce Signature over the Registration's current (or
 * immediately-previous, for retry-safety) Nonce (ADR-0010).
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
        RegistrationStore.NonceState nonceState = store.nonceState(request.clientId());
        if (nonceState != null) {
            return RegisterResponse.alreadyRegistered(nonceState.current());
        }
        Challenge challenge = challengeStore.issue(request.clientId(), challengeTtl);
        return RegisterResponse.challenge(challenge);
    }

    private RegisterResponse verifyChallengeResponse(RegisterRequest request) {
        Challenge challenge = challengeStore.consume(request.clientId());
        if (challenge == null || !Ed25519.verify(authPublicKey, challenge, request.challengeResponse())) {
            return RegisterResponse.challengeRejected();
        }
        Nonce initialNonce = Nonce.random();
        boolean created = store.tryRegister(request.clientId(), validityPeriod, initialNonce);
        if (created) {
            return RegisterResponse.success((int) validityPeriod.toSeconds(), initialNonce);
        }
        // Lost the race to a concurrent successful attempt for the same Client ID; report its Nonce.
        RegistrationStore.NonceState nonceState = store.nonceState(request.clientId());
        return RegisterResponse.alreadyRegistered(nonceState.current());
    }

    private RenewResponse renew(RenewRequest request) {
        RegistrationStore.NonceState nonceState = store.nonceState(request.clientId());
        if (nonceState == null) {
            return RenewResponse.notRegistered();
        }
        if (Ed25519.verify(authPublicKey, nonceState.current(), request.nonceSignature())) {
            Nonce newNonce = Nonce.random();
            boolean rotated = store.rotateNonce(request.clientId(), validityPeriod, newNonce);
            return rotated
                    ? RenewResponse.success((int) validityPeriod.toSeconds(), newNonce)
                    : RenewResponse.notRegistered();
        }
        if (nonceState.previous() != null && Ed25519.verify(authPublicKey, nonceState.previous(), request.nonceSignature())) {
            // Our own earlier successful rotation landing again (its response was lost) - re-serve
            // the same current Nonce without rotating further, so this is safe to hit repeatedly.
            return RenewResponse.success((int) validityPeriod.toSeconds(), nonceState.current());
        }
        return RenewResponse.invalidToken(nonceState.current());
    }

    private CancelResponse cancel(CancelRequest request) {
        RegistrationStore.NonceState nonceState = store.nonceState(request.clientId());
        if (nonceState == null) {
            return CancelResponse.notRegistered();
        }
        boolean validSignature = Ed25519.verify(authPublicKey, nonceState.current(), request.nonceSignature())
                || (nonceState.previous() != null
                        && Ed25519.verify(authPublicKey, nonceState.previous(), request.nonceSignature()));
        if (!validSignature) {
            return CancelResponse.invalidToken(nonceState.current());
        }
        store.cancel(request.clientId());
        return CancelResponse.success();
    }

    private static IllegalArgumentException unexpected(ProtocolMessage request) {
        return new IllegalArgumentException("Server does not accept " + request.type());
    }
}
