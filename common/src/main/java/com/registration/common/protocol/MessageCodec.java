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
    private static final int STATUS_ONLY_PAYLOAD_LENGTH = 1;

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
            case REGISTER_REQUEST -> readRegisterRequest(payload);
            case RENEW_REQUEST -> new RenewRequest(readClientId(payload));
            case CANCEL_REQUEST -> new CancelRequest(readClientId(payload));
            case REGISTER_RESPONSE -> readRegisterResponse(payload);
            case RENEW_RESPONSE -> new RenewResponse(readStatus(payload), readValidityPeriod(payload));
            case CANCEL_RESPONSE -> new CancelResponse(readStatus(payload));
        };
    }

    private static ByteBuffer encodePayload(ProtocolMessage message) {
        return switch (message) {
            case RegisterRequest r -> writeRegisterRequest(r);
            case RenewRequest r -> writeClientId(r.clientId());
            case CancelRequest r -> writeClientId(r.clientId());
            case RegisterResponse r -> writeRegisterResponse(r);
            case RenewResponse r -> writeResponse(r.status(), r.validityPeriodSeconds());
            case CancelResponse r -> writeStatusOnly(r.status());
        };
    }

    private static ByteBuffer writeRegisterRequest(RegisterRequest request) {
        boolean hasResponse = request.hasChallengeResponse();
        int length = CLIENT_ID_PAYLOAD_LENGTH + (hasResponse ? ChallengeResponse.LENGTH : 0);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.putLong(request.clientId().rawValue());
        if (hasResponse) {
            buffer.put(request.challengeResponse().value());
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer writeRegisterResponse(RegisterResponse response) {
        ByteBuffer buffer = switch (response.status()) {
            case SUCCESS, ALREADY_REGISTERED -> {
                ByteBuffer b = ByteBuffer.allocate(RESPONSE_PAYLOAD_LENGTH);
                b.put(response.status().code());
                b.putShort((short) response.validityPeriodSeconds());
                yield b;
            }
            case CHALLENGE -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH + Challenge.LENGTH);
                b.put(response.status().code());
                b.put(response.challenge().value());
                yield b;
            }
            case CHALLENGE_REJECTED -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH);
                b.put(response.status().code());
                yield b;
            }
            case NOT_REGISTERED -> throw new IllegalArgumentException("REGISTER_RESPONSE cannot carry NOT_REGISTERED");
        };
        buffer.flip();
        return buffer;
    }

    private static RegisterRequest readRegisterRequest(ByteBuffer payload) {
        ClientId clientId = readClientId(payload);
        if (payload.remaining() == 0) {
            return RegisterRequest.initial(clientId);
        }
        if (payload.remaining() != ChallengeResponse.LENGTH) {
            throw new IllegalArgumentException(
                    "Malformed REGISTER_REQUEST payload: " + payload.remaining() + " bytes after Client ID");
        }
        byte[] responseBytes = new byte[ChallengeResponse.LENGTH];
        payload.get(responseBytes);
        return RegisterRequest.withChallengeResponse(clientId, ChallengeResponse.of(responseBytes));
    }

    private static RegisterResponse readRegisterResponse(ByteBuffer payload) {
        StatusCode status = readStatus(payload);
        return switch (status) {
            case SUCCESS, ALREADY_REGISTERED -> new RegisterResponse(status, readValidityPeriod(payload), null);
            case CHALLENGE -> {
                byte[] challengeBytes = new byte[Challenge.LENGTH];
                payload.get(challengeBytes);
                yield RegisterResponse.challenge(Challenge.of(challengeBytes));
            }
            case CHALLENGE_REJECTED -> RegisterResponse.challengeRejected();
            case NOT_REGISTERED -> throw new IllegalArgumentException("REGISTER_RESPONSE cannot carry NOT_REGISTERED");
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
