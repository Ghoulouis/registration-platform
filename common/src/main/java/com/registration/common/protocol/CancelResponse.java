package com.registration.common.protocol;

import java.util.Objects;

/**
 * Server -> Client: outcome of a {@link CancelRequest} (ADR-0004). No Validity Period to
 * return — cancellation removes the Registration outright, unlike REGISTER/RENEW. {@code nonce}
 * is the actual current Nonce on {@link StatusCode#INVALID_TOKEN} only (ADR-0010), absent otherwise.
 */
public record CancelResponse(StatusCode status, Nonce nonce) implements ProtocolMessage {

    public static CancelResponse success() {
        return new CancelResponse(StatusCode.SUCCESS, null);
    }

    public static CancelResponse notRegistered() {
        return new CancelResponse(StatusCode.NOT_REGISTERED, null);
    }

    public static CancelResponse invalidToken(Nonce currentNonce) {
        return new CancelResponse(StatusCode.INVALID_TOKEN, Objects.requireNonNull(currentNonce));
    }

    @Override
    public MessageType type() {
        return MessageType.CANCEL_RESPONSE;
    }
}
