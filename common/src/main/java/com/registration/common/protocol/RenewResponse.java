package com.registration.common.protocol;

import java.util.Objects;

/**
 * Server -> Client: outcome of a {@link RenewRequest}. {@code validityPeriodSeconds} is
 * relative to receipt (ADR-0003), meaningful only for {@link StatusCode#SUCCESS}. {@code nonce}
 * is the new current Nonce on SUCCESS, or the actual current Nonce on {@link StatusCode#INVALID_TOKEN}
 * (harmless to disclose — only a valid signature over it proves anything, ADR-0010); absent on
 * {@link StatusCode#NOT_REGISTERED}.
 */
public record RenewResponse(StatusCode status, int validityPeriodSeconds, Nonce nonce) implements ProtocolMessage {

    public RenewResponse {
        if (validityPeriodSeconds < 0 || validityPeriodSeconds > 0xFFFF) {
            throw new IllegalArgumentException("validityPeriodSeconds out of range: " + validityPeriodSeconds);
        }
    }

    public static RenewResponse success(int validityPeriodSeconds, Nonce nonce) {
        return new RenewResponse(StatusCode.SUCCESS, validityPeriodSeconds, Objects.requireNonNull(nonce));
    }

    public static RenewResponse notRegistered() {
        return new RenewResponse(StatusCode.NOT_REGISTERED, 0, null);
    }

    public static RenewResponse invalidToken(Nonce currentNonce) {
        return new RenewResponse(StatusCode.INVALID_TOKEN, 0, Objects.requireNonNull(currentNonce));
    }

    @Override
    public MessageType type() {
        return MessageType.RENEW_RESPONSE;
    }
}
