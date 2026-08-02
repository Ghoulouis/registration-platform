package com.registration.common.protocol;

import java.util.Objects;

/**
 * Client -> Server: voluntarily remove the Registration for {@code clientId} (ADR-0004).
 * Fails if none exists. {@code nonceSignature} proves the Client holds the Shared Signing
 * Key, same authentication as a Renewal (ADR-0010). {@code traceContext} identifies this
 * connection attempt for correlation (ADR-0012).
 */
public record CancelRequest(ClientId clientId, TraceContext traceContext, NonceSignature nonceSignature)
        implements ProtocolMessage {

    public CancelRequest {
        Objects.requireNonNull(traceContext);
        Objects.requireNonNull(nonceSignature);
    }

    @Override
    public MessageType type() {
        return MessageType.CANCEL_REQUEST;
    }
}
