package com.registration.common.protocol;

import java.util.Objects;

/**
 * Server -> Client: outcome of one leg of a {@link RegisterRequest} (ADR-0009). {@code
 * validityPeriodSeconds} is meaningful only for {@link StatusCode#SUCCESS}, relative to
 * receipt, not an absolute timestamp (ADR-0003). {@code nonce} is carried on every status
 * except {@link StatusCode#CHALLENGE_REJECTED}: on {@link StatusCode#CHALLENGE} it's the
 * pending Nonce to sign; on {@link StatusCode#SUCCESS}/{@link StatusCode#ALREADY_REGISTERED}
 * it's the confirmed Registration's current Nonce (ADR-0011).
 */
public record RegisterResponse(StatusCode status, int validityPeriodSeconds, Nonce nonce) implements ProtocolMessage {

    public RegisterResponse {
        if (validityPeriodSeconds < 0 || validityPeriodSeconds > 0xFFFF) {
            throw new IllegalArgumentException("validityPeriodSeconds out of range: " + validityPeriodSeconds);
        }
    }

    public static RegisterResponse success(int validityPeriodSeconds, Nonce nonce) {
        return new RegisterResponse(StatusCode.SUCCESS, validityPeriodSeconds, Objects.requireNonNull(nonce));
    }

    public static RegisterResponse alreadyRegistered(Nonce currentNonce) {
        return new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0, Objects.requireNonNull(currentNonce));
    }

    public static RegisterResponse challenge(Nonce pendingNonce) {
        return new RegisterResponse(StatusCode.CHALLENGE, 0, Objects.requireNonNull(pendingNonce));
    }

    public static RegisterResponse challengeRejected() {
        return new RegisterResponse(StatusCode.CHALLENGE_REJECTED, 0, null);
    }

    @Override
    public MessageType type() {
        return MessageType.REGISTER_RESPONSE;
    }
}
