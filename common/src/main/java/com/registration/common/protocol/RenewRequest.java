package com.registration.common.protocol;

import java.util.Objects;

/**
 * Client -> Server: extend the Registration for {@code clientId}. Fails if none exists.
 * {@code nonceSignature} proves the Client holds the Shared Signing Key, signed over the
 * Registration's current (or immediately-previous) Nonce (ADR-0010).
 */
public record RenewRequest(ClientId clientId, NonceSignature nonceSignature) implements ProtocolMessage {

    public RenewRequest {
        Objects.requireNonNull(nonceSignature);
    }

    @Override
    public MessageType type() {
        return MessageType.RENEW_REQUEST;
    }
}
