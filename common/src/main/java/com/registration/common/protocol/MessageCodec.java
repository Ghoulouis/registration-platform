package com.registration.common.protocol;

import java.nio.ByteBuffer;

/**
 * Encodes/decodes a complete {@link ProtocolMessage} frame: a 5-byte header
 * (1-byte {@link MessageType} code + 4-byte big-endian payload length) followed
 * by a fixed-layout payload (ADR-0003, ADR-0004). Framing across partial NIO reads is
 * {@link FrameDecoder}'s job, not this class's.
 */
public final class MessageCodec {

    static final int HEADER_LENGTH = 5;
    private static final int CLIENT_ID_PAYLOAD_LENGTH = 8;
    private static final int RESPONSE_PAYLOAD_LENGTH = 3;
    private static final int CANCEL_RESPONSE_PAYLOAD_LENGTH = 1;

    private MessageCodec() {
    }

    public static ByteBuffer encode(ProtocolMessage message) {
        ByteBuffer payload = encodePayload(message);
        ByteBuffer frame = ByteBuffer.allocate(HEADER_LENGTH + payload.remaining());
        frame.put(message.type().code());
        frame.putInt(payload.remaining());
        frame.put(payload);
        frame.flip();
        return frame;
    }

    /** {@code payload} must be positioned at the start of exactly one payload's worth of bytes. */
    public static ProtocolMessage decode(MessageType type, ByteBuffer payload) {
        return switch (type) {
            case REGISTER_REQUEST -> new RegisterRequest(readClientId(payload));
            case RENEW_REQUEST -> new RenewRequest(readClientId(payload));
            case CANCEL_REQUEST -> new CancelRequest(readClientId(payload));
            case REGISTER_RESPONSE -> new RegisterResponse(readStatus(payload), readValidityPeriod(payload));
            case RENEW_RESPONSE -> new RenewResponse(readStatus(payload), readValidityPeriod(payload));
            case CANCEL_RESPONSE -> new CancelResponse(readStatus(payload));
        };
    }

    private static ByteBuffer encodePayload(ProtocolMessage message) {
        return switch (message) {
            case RegisterRequest r -> writeClientId(r.clientId());
            case RenewRequest r -> writeClientId(r.clientId());
            case CancelRequest r -> writeClientId(r.clientId());
            case RegisterResponse r -> writeResponse(r.status(), r.validityPeriodSeconds());
            case RenewResponse r -> writeResponse(r.status(), r.validityPeriodSeconds());
            case CancelResponse r -> writeStatusOnly(r.status());
        };
    }

    private static ByteBuffer writeClientId(ClientId clientId) {
        ByteBuffer buffer = ByteBuffer.allocate(CLIENT_ID_PAYLOAD_LENGTH);
        buffer.putLong(clientId.rawValue());
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer writeResponse(StatusCode status, int validityPeriodSeconds) {
        ByteBuffer buffer = ByteBuffer.allocate(RESPONSE_PAYLOAD_LENGTH);
        buffer.put(status.code());
        buffer.putShort((short) validityPeriodSeconds);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer writeStatusOnly(StatusCode status) {
        ByteBuffer buffer = ByteBuffer.allocate(CANCEL_RESPONSE_PAYLOAD_LENGTH);
        buffer.put(status.code());
        buffer.flip();
        return buffer;
    }

    private static ClientId readClientId(ByteBuffer payload) {
        return ClientId.ofRawValue(payload.getLong());
    }

    private static StatusCode readStatus(ByteBuffer payload) {
        return StatusCode.fromCode(payload.get());
    }

    private static int readValidityPeriod(ByteBuffer payload) {
        return Short.toUnsignedInt(payload.getShort());
    }
}
