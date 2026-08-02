package com.registration.server.domain;

import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.store.RegistrationStore;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Duration;

/**
 * Applies REGISTER/RENEW/CANCEL symmetry (ADR-0003, ADR-0004), same as the Distributed
 * Server's equivalent — synchronous here since {@link RegistrationStore} has no I/O to
 * await. REGISTER is a two-step exchange (ADR-0009): issue a pending Nonce, then verify a
 * signature over it. RENEW and CANCEL are each authenticated by a signature over the
 * Registration's current (or immediately-previous, for retry-safety) Nonce. Both phases
 * share one record per Client ID (ADR-0011).
 */
@Service
public class RegistrationService {

    private final RegistrationStore store;
    private final Duration validityPeriod;
    private final Duration pendingNonceTtl;
    private final PublicKey authPublicKey;

    public RegistrationService(RegistrationStore store, RegistrationProperties properties) {
        this.store = store;
        this.validityPeriod = Duration.ofSeconds(properties.validityPeriodSeconds());
        this.pendingNonceTtl = Duration.ofSeconds(properties.pendingNonceTtlSeconds());
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
        return request.hasNonceSignature()
                ? confirm(request)
                : issuePendingNonce(request);
    }

    private RegisterResponse issuePendingNonce(RegisterRequest request) {
        RegistrationStore.ClientRecord record = store.get(request.clientId());
        if (record != null && record.registered()) {
            return RegisterResponse.alreadyRegistered(record.nonce());
        }
        Nonce nonce = store.issuePendingNonce(request.clientId(), pendingNonceTtl);
        return RegisterResponse.challenge(nonce);
    }

    private RegisterResponse confirm(RegisterRequest request) {
        RegistrationStore.ClientRecord record = store.get(request.clientId());
        if (record == null || record.registered()) {
            return RegisterResponse.challengeRejected();
        }
        if (!Ed25519.verify(authPublicKey, record.nonce(), request.nonceSignature())) {
            // A pending Nonce is single-use regardless of outcome (ADR-0009) — discard it so
            // a wrong signature can't be retried indefinitely against the same Nonce.
            store.remove(request.clientId());
            return RegisterResponse.challengeRejected();
        }
        Nonce newNonce = Nonce.random();
        boolean confirmed = store.confirm(request.clientId(), validityPeriod, newNonce);
        if (confirmed) {
            return RegisterResponse.success((int) validityPeriod.toSeconds(), newNonce);
        }
        // Lost the race to a concurrent successful attempt for the same Client ID; report its Nonce.
        RegistrationStore.ClientRecord after = store.get(request.clientId());
        return RegisterResponse.alreadyRegistered(after.nonce());
    }

    private RenewResponse renew(RenewRequest request) {
        RegistrationStore.ClientRecord record = store.get(request.clientId());
        if (record == null || !record.registered()) {
            return RenewResponse.notRegistered();
        }
        if (Ed25519.verify(authPublicKey, record.nonce(), request.nonceSignature())) {
            Nonce newNonce = Nonce.random();
            boolean rotated = store.rotateNonce(request.clientId(), validityPeriod, newNonce);
            return rotated
                    ? RenewResponse.success((int) validityPeriod.toSeconds(), newNonce)
                    : RenewResponse.notRegistered();
        }
        if (record.previousNonce() != null && Ed25519.verify(authPublicKey, record.previousNonce(), request.nonceSignature())) {
            // Our own earlier successful rotation landing again (its response was lost) - re-serve
            // the same current Nonce without rotating further, so this is safe to hit repeatedly.
            return RenewResponse.success((int) validityPeriod.toSeconds(), record.nonce());
        }
        return RenewResponse.invalidToken(record.nonce());
    }

    private CancelResponse cancel(CancelRequest request) {
        RegistrationStore.ClientRecord record = store.get(request.clientId());
        if (record == null || !record.registered()) {
            return CancelResponse.notRegistered();
        }
        boolean validSignature = Ed25519.verify(authPublicKey, record.nonce(), request.nonceSignature())
                || (record.previousNonce() != null
                        && Ed25519.verify(authPublicKey, record.previousNonce(), request.nonceSignature()));
        if (!validSignature) {
            return CancelResponse.invalidToken(record.nonce());
        }
        store.remove(request.clientId());
        return CancelResponse.success();
    }

    private static IllegalArgumentException unexpected(ProtocolMessage request) {
        return new IllegalArgumentException("Server does not accept " + request.type());
    }
}
