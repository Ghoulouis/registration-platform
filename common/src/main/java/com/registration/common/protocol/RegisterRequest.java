package com.registration.common.protocol;

/** Client -> Server: create a Registration for {@code clientId}. Fails if one already exists. */
public record RegisterRequest(ClientId clientId) implements ProtocolMessage {

    @Override
    public MessageType type() {
        return MessageType.REGISTER_REQUEST;
    }
}
