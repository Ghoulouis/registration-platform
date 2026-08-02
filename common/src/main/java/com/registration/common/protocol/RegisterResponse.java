package com.registration.common.protocol;

/**
 * Server -> Client: outcome of a {@link RegisterRequest}. {@code validityPeriodSeconds}
 * is relative to receipt, not an absolute timestamp (ADR-0003) — meaningful only
 * when {@code status} is {@link StatusCode#SUCCESS}, 0 otherwise.
 */
public record RegisterResponse(StatusCode status, int validityPeriodSeconds) implements ProtocolMessage {

    public RegisterResponse {
        if (validityPeriodSeconds < 0 || validityPeriodSeconds > 0xFFFF) {
            throw new IllegalArgumentException("validityPeriodSeconds out of range: " + validityPeriodSeconds);
        }
    }

    @Override
    public MessageType type() {
        return MessageType.REGISTER_RESPONSE;
    }
}
