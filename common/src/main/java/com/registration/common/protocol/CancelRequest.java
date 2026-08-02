package com.registration.common.protocol;

/** Client -> Server: voluntarily remove the Registration for {@code clientId} (ADR-0004). Fails if none exists. */
public record CancelRequest(ClientId clientId) implements ProtocolMessage {

    @Override
    public MessageType type() {
        return MessageType.CANCEL_REQUEST;
    }
}
