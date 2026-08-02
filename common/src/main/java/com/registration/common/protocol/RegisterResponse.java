package com.registration.common.protocol;

import java.util.Objects;

/**
 * Server -> Client: outcome of one leg of a {@link RegisterRequest} (ADR-0009).
 * {@code validityPeriodSeconds} is meaningful only for {@link StatusCode#SUCCESS},
 * relative to receipt, not an absolute timestamp (ADR-0003). {@code challenge} is
 * meaningful only for {@link StatusCode#CHALLENGE}. {@code nonce} is the Registration's
 * current Nonce (ADR-0010), carried on both SUCCESS and ALREADY_REGISTERED so a Client
 * whose original success response was lost can still learn it via a retry.
 */
public record RegisterResponse(StatusCode status, int validityPeriodSeconds, Challenge challenge, Nonce nonce)
        implements ProtocolMessage {

    public RegisterResponse {
        if (validityPeriodSeconds < 0 || validityPeriodSeconds > 0xFFFF) {
            throw new IllegalArgumentException("validityPeriodSeconds out of range: " + validityPeriodSeconds);
        }
    }

    public static RegisterResponse success(int validityPeriodSeconds, Nonce nonce) {
        return new RegisterResponse(StatusCode.SUCCESS, validityPeriodSeconds, null, Objects.requireNonNull(nonce));
    }

    public static RegisterResponse alreadyRegistered(Nonce currentNonce) {
        return new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0, null, Objects.requireNonNull(currentNonce));
    }

    public static RegisterResponse challenge(Challenge challenge) {
        return new RegisterResponse(StatusCode.CHALLENGE, 0, Objects.requireNonNull(challenge), null);
    }

    public static RegisterResponse challengeRejected() {
        return new RegisterResponse(StatusCode.CHALLENGE_REJECTED, 0, null, null);
    }

    @Override
    public MessageType type() {
        return MessageType.REGISTER_RESPONSE;
    }
}
