package com.registration.common.protocol;

/** Client -> Server: extend the Registration for {@code clientId}. Fails if none exists. */
public record RenewRequest(ClientId clientId) implements ProtocolMessage {

    @Override
    public MessageType type() {
        return MessageType.RENEW_REQUEST;
    }
}
