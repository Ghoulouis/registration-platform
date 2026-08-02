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
import com.registration.server.observability.RegistrationEventLog;
import com.registration.server.store.RegistrationStore;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Duration;

import static com.registration.server.observability.RegistrationEventLog.Level.DEBUG;
import static com.registration.server.observability.RegistrationEventLog.Level.INFO;
import static com.registration.server.observability.RegistrationEventLog.Level.WARN;

/**
 * Applies REGISTER/RENEW/CANCEL symmetry (ADR-0003, ADR-0004), same as the Distributed
 * Server's equivalent — synchronous here since {@link RegistrationStore} has no I/O to
 * await. REGISTER is a two-step exchange (ADR-0009): issue a pending Nonce, then verify a
 * signature over it. RENEW and CANCEL are each authenticated by a signature over the
 * Registration's current (or immediately-previous, for retry-safety) Nonce (ADR-0010). Every
 * decision point logs a Registration Event (ADR-0014) — the only place step-level granularity
 * is visible, since a wrapper looking at just the final response couldn't see it.
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
        RegistrationEventLog.step(request.clientId(), "REGISTER", "nonce_requested");
        RegistrationStore.ClientRecord record = store.get(request.clientId());
        if (record != null && record.registered()) {
            RegistrationEventLog.outcome(request.clientId(), "REGISTER", "already_registered", "REJECTED", DEBUG);
            return RegisterResponse.alreadyRegistered(record.nonce());
        }
        Nonce nonce = store.issuePendingNonce(request.clientId(), pendingNonceTtl);
        RegistrationEventLog.step(request.clientId(), "REGISTER", "nonce_issued");
        return RegisterResponse.challenge(nonce);
    }

    private RegisterResponse confirm(RegisterRequest request) {
        RegistrationEventLog.step(request.clientId(), "REGISTER", "auth_data_received");
        RegistrationStore.ClientRecord record = store.get(request.clientId());
        if (record == null || record.registered()) {
            RegistrationEventLog.outcome(request.clientId(), "REGISTER", "challenge_rejected", "REJECTED", WARN);
            return RegisterResponse.challengeRejected();
        }
        if (!Ed25519.verify(authPublicKey, record.nonce(), request.nonceSignature())) {
            // A pending Nonce is single-use regardless of outcome (ADR-0009) — discard it so
            // a wrong signature can't be retried indefinitely against the same Nonce.
            store.remove(request.clientId());
            RegistrationEventLog.outcome(request.clientId(), "REGISTER", "invalid_signature", "REJECTED", WARN);
            return RegisterResponse.challengeRejected();
        }
        RegistrationEventLog.step(request.clientId(), "REGISTER", "auth_success");
        Nonce newNonce = Nonce.random();
        boolean confirmed = store.confirm(request.clientId(), validityPeriod, newNonce);
        if (confirmed) {
            RegistrationEventLog.outcome(request.clientId(), "REGISTER", "db_updated", "SUCCESS", INFO);
            return RegisterResponse.success((int) validityPeriod.toSeconds(), newNonce);
        }
        // Lost the race to a concurrent successful attempt for the same Client ID; report its Nonce.
        RegistrationStore.ClientRecord after = store.get(request.clientId());
        RegistrationEventLog.outcome(request.clientId(), "REGISTER", "already_registered", "REJECTED", DEBUG);
        return RegisterResponse.alreadyRegistered(after.nonce());
    }

    private RenewResponse renew(RenewRequest request) {
        RegistrationEventLog.step(request.clientId(), "RENEW", "auth_data_received");
        RegistrationStore.ClientRecord record = store.get(request.clientId());
        if (record == null || !record.registered()) {
            RegistrationEventLog.outcome(request.clientId(), "RENEW", "not_registered", "REJECTED", DEBUG);
            return RenewResponse.notRegistered();
        }
        if (Ed25519.verify(authPublicKey, record.nonce(), request.nonceSignature())) {
            RegistrationEventLog.step(request.clientId(), "RENEW", "auth_success");
            Nonce newNonce = Nonce.random();
            boolean rotated = store.rotateNonce(request.clientId(), validityPeriod, newNonce);
            if (!rotated) {
                RegistrationEventLog.outcome(request.clientId(), "RENEW", "not_registered", "REJECTED", DEBUG);
                return RenewResponse.notRegistered();
            }
            RegistrationEventLog.outcome(request.clientId(), "RENEW", "db_updated", "SUCCESS", DEBUG);
            return RenewResponse.success((int) validityPeriod.toSeconds(), newNonce);
        }
        if (record.previousNonce() != null && Ed25519.verify(authPublicKey, record.previousNonce(), request.nonceSignature())) {
            // Our own earlier successful rotation landing again (its response was lost) - re-serve
            // the same current Nonce without rotating further, so this is safe to hit repeatedly.
            RegistrationEventLog.outcome(request.clientId(), "RENEW", "auth_success_retry", "SUCCESS", DEBUG);
            return RenewResponse.success((int) validityPeriod.toSeconds(), record.nonce());
        }
        RegistrationEventLog.outcome(request.clientId(), "RENEW", "invalid_signature", "REJECTED", WARN);
        return RenewResponse.invalidToken(record.nonce());
    }

    private CancelResponse cancel(CancelRequest request) {
        RegistrationEventLog.step(request.clientId(), "CANCEL", "auth_data_received");
        RegistrationStore.ClientRecord record = store.get(request.clientId());
        if (record == null || !record.registered()) {
            RegistrationEventLog.outcome(request.clientId(), "CANCEL", "not_registered", "REJECTED", DEBUG);
            return CancelResponse.notRegistered();
        }
        boolean validSignature = Ed25519.verify(authPublicKey, record.nonce(), request.nonceSignature())
                || (record.previousNonce() != null
                        && Ed25519.verify(authPublicKey, record.previousNonce(), request.nonceSignature()));
        if (!validSignature) {
            RegistrationEventLog.outcome(request.clientId(), "CANCEL", "invalid_signature", "REJECTED", WARN);
            return CancelResponse.invalidToken(record.nonce());
        }
        RegistrationEventLog.step(request.clientId(), "CANCEL", "auth_success");
        store.remove(request.clientId());
        RegistrationEventLog.outcome(request.clientId(), "CANCEL", "db_updated", "SUCCESS", INFO);
        return CancelResponse.success();
    }

    private static IllegalArgumentException unexpected(ProtocolMessage request) {
        return new IllegalArgumentException("Server does not accept " + request.type());
    }
}
