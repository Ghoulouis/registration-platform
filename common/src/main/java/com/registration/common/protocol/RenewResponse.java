package com.registration.common.protocol;

/**
 * Server -> Client: outcome of a {@link RenewRequest}. {@code validityPeriodSeconds}
 * is relative to receipt, not an absolute timestamp (ADR-0003) — meaningful only
 * when {@code status} is {@link StatusCode#SUCCESS}, 0 otherwise.
 */
public record RenewResponse(StatusCode status, int validityPeriodSeconds) implements ProtocolMessage {

    public RenewResponse {
        if (validityPeriodSeconds < 0 || validityPeriodSeconds > 0xFFFF) {
            throw new IllegalArgumentException("validityPeriodSeconds out of range: " + validityPeriodSeconds);
        }
    }

    @Override
    public MessageType type() {
        return MessageType.RENEW_RESPONSE;
    }
}
