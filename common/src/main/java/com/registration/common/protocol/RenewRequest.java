package com.registration.common.protocol;

import java.util.Objects;

/**
 * Client -> Server: extend the Registration for {@code clientId}. Fails if none exists.
 * {@code nonceSignature} proves the Client holds the Shared Signing Key, signed over the
 * Registration's current (or immediately-previous) Nonce (ADR-0010). {@code traceContext}
 * identifies this connection attempt for correlation (ADR-0012).
 */
public record RenewRequest(ClientId clientId, TraceContext traceContext, NonceSignature nonceSignature)
        implements ProtocolMessage {

    public RenewRequest {
        Objects.requireNonNull(traceContext);
        Objects.requireNonNull(nonceSignature);
    }

    @Override
    public MessageType type() {
        return MessageType.RENEW_REQUEST;
    }
}
