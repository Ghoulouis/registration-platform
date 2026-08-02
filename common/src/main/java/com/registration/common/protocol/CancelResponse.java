package com.registration.common.protocol;

/**
 * Server -> Client: outcome of a {@link CancelRequest} (ADR-0004). No Validity Period to
 * return — cancellation removes the Registration outright, unlike REGISTER/RENEW.
 */
public record CancelResponse(StatusCode status) implements ProtocolMessage {

    @Override
    public MessageType type() {
        return MessageType.CANCEL_RESPONSE;
    }
}
