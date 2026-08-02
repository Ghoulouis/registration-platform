package com.registration.common.protocol;

import java.util.Objects;

/**
 * Client -> Server: register {@code clientId}. Fails if one already exists. Two shapes
 * (ADR-0009, ADR-0011): the initial attempt carries no {@code nonceSignature} and gets back
 * a Nonce to sign; resubmitting with a {@code nonceSignature} completes the Registration.
 * {@code traceContext} identifies this connection attempt for correlation (ADR-0012).
 */
public record RegisterRequest(ClientId clientId, TraceContext traceContext, NonceSignature nonceSignature)
        implements ProtocolMessage {

    public RegisterRequest {
        Objects.requireNonNull(traceContext);
    }

    public static RegisterRequest initial(ClientId clientId, TraceContext traceContext) {
        return new RegisterRequest(clientId, traceContext, null);
    }

    public static RegisterRequest withNonceSignature(
            ClientId clientId, TraceContext traceContext, NonceSignature nonceSignature) {
        return new RegisterRequest(clientId, traceContext, Objects.requireNonNull(nonceSignature));
    }

    public boolean hasNonceSignature() {
        return nonceSignature != null;
    }

    @Override
    public MessageType type() {
        return MessageType.REGISTER_REQUEST;
    }
}
