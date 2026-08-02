package com.registration.common.protocol;

import java.nio.ByteBuffer;

/**
 * Encodes/decodes a complete {@link ProtocolMessage} frame: a 5-byte header
 * (1-byte {@link MessageType} code + 4-byte big-endian payload length) followed
 * by a fixed-layout payload (ADR-0003, ADR-0004, ADR-0009, ADR-0010). Framing across
 * partial NIO reads is {@link FrameDecoder}'s job, not this class's.
 */
public final class MessageCodec {

    static final int HEADER_LENGTH = 5;
    private static final int CLIENT_ID_PAYLOAD_LENGTH = 8;
    private static final int STATUS_ONLY_PAYLOAD_LENGTH = 1;
    private static final int VALIDITY_PERIOD_LENGTH = 2;

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
            case RENEW_REQUEST -> readRenewRequest(payload);
            case CANCEL_REQUEST -> readCancelRequest(payload);
            case REGISTER_RESPONSE -> readRegisterResponse(payload);
            case RENEW_RESPONSE -> readRenewResponse(payload);
            case CANCEL_RESPONSE -> readCancelResponse(payload);
        };
    }

    private static ByteBuffer encodePayload(ProtocolMessage message) {
        return switch (message) {
            case RegisterRequest r -> writeRegisterRequest(r);
            case RenewRequest r -> writeRenewRequest(r);
            case CancelRequest r -> writeCancelRequest(r);
            case RegisterResponse r -> writeRegisterResponse(r);
            case RenewResponse r -> writeRenewResponse(r);
            case CancelResponse r -> writeCancelResponse(r);
        };
    }

    // ---- REGISTER_REQUEST ----

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

    private static RegisterRequest readRegisterRequest(ByteBuffer payload) {
        ClientId clientId = readClientId(payload);
        if (payload.remaining() == 0) {
            return RegisterRequest.initial(clientId);
        }
        if (payload.remaining() != ChallengeResponse.LENGTH) {
            throw new IllegalArgumentException(
                    "Malformed REGISTER_REQUEST payload: " + payload.remaining() + " bytes after Client ID");
        }
        return RegisterRequest.withChallengeResponse(clientId, ChallengeResponse.of(readBytes(payload, ChallengeResponse.LENGTH)));
    }

    // ---- REGISTER_RESPONSE ----

    private static ByteBuffer writeRegisterResponse(RegisterResponse response) {
        ByteBuffer buffer = switch (response.status()) {
            case SUCCESS -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH + VALIDITY_PERIOD_LENGTH + Nonce.LENGTH);
                b.put(response.status().code());
                b.putShort((short) response.validityPeriodSeconds());
                b.put(response.nonce().value());
                yield b;
            }
            case ALREADY_REGISTERED -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH + Nonce.LENGTH);
                b.put(response.status().code());
                b.put(response.nonce().value());
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
            case NOT_REGISTERED, INVALID_TOKEN ->
                    throw new IllegalArgumentException("REGISTER_RESPONSE cannot carry " + response.status());
        };
        buffer.flip();
        return buffer;
    }

    private static RegisterResponse readRegisterResponse(ByteBuffer payload) {
        StatusCode status = readStatus(payload);
        return switch (status) {
            case SUCCESS -> {
                int validityPeriod = readValidityPeriod(payload);
                yield RegisterResponse.success(validityPeriod, Nonce.of(readBytes(payload, Nonce.LENGTH)));
            }
            case ALREADY_REGISTERED -> RegisterResponse.alreadyRegistered(Nonce.of(readBytes(payload, Nonce.LENGTH)));
            case CHALLENGE -> RegisterResponse.challenge(Challenge.of(readBytes(payload, Challenge.LENGTH)));
            case CHALLENGE_REJECTED -> RegisterResponse.challengeRejected();
            case NOT_REGISTERED, INVALID_TOKEN ->
                    throw new IllegalArgumentException("REGISTER_RESPONSE cannot carry " + status);
        };
    }

    // ---- RENEW_REQUEST ----

    private static ByteBuffer writeRenewRequest(RenewRequest request) {
        ByteBuffer buffer = ByteBuffer.allocate(CLIENT_ID_PAYLOAD_LENGTH + NonceSignature.LENGTH);
        buffer.putLong(request.clientId().rawValue());
        buffer.put(request.nonceSignature().value());
        buffer.flip();
        return buffer;
    }

    private static RenewRequest readRenewRequest(ByteBuffer payload) {
        ClientId clientId = readClientId(payload);
        return new RenewRequest(clientId, NonceSignature.of(readBytes(payload, NonceSignature.LENGTH)));
    }

    // ---- RENEW_RESPONSE ----

    private static ByteBuffer writeRenewResponse(RenewResponse response) {
        ByteBuffer buffer = switch (response.status()) {
            case SUCCESS -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH + VALIDITY_PERIOD_LENGTH + Nonce.LENGTH);
                b.put(response.status().code());
                b.putShort((short) response.validityPeriodSeconds());
                b.put(response.nonce().value());
                yield b;
            }
            case NOT_REGISTERED -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH);
                b.put(response.status().code());
                yield b;
            }
            case INVALID_TOKEN -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH + Nonce.LENGTH);
                b.put(response.status().code());
                b.put(response.nonce().value());
                yield b;
            }
            case ALREADY_REGISTERED, CHALLENGE, CHALLENGE_REJECTED ->
                    throw new IllegalArgumentException("RENEW_RESPONSE cannot carry " + response.status());
        };
        buffer.flip();
        return buffer;
    }

    private static RenewResponse readRenewResponse(ByteBuffer payload) {
        StatusCode status = readStatus(payload);
        return switch (status) {
            case SUCCESS -> {
                int validityPeriod = readValidityPeriod(payload);
                yield RenewResponse.success(validityPeriod, Nonce.of(readBytes(payload, Nonce.LENGTH)));
            }
            case NOT_REGISTERED -> RenewResponse.notRegistered();
            case INVALID_TOKEN -> RenewResponse.invalidToken(Nonce.of(readBytes(payload, Nonce.LENGTH)));
            case ALREADY_REGISTERED, CHALLENGE, CHALLENGE_REJECTED ->
                    throw new IllegalArgumentException("RENEW_RESPONSE cannot carry " + status);
        };
    }

    // ---- CANCEL_REQUEST ----

    private static ByteBuffer writeCancelRequest(CancelRequest request) {
        ByteBuffer buffer = ByteBuffer.allocate(CLIENT_ID_PAYLOAD_LENGTH + NonceSignature.LENGTH);
        buffer.putLong(request.clientId().rawValue());
        buffer.put(request.nonceSignature().value());
        buffer.flip();
        return buffer;
    }

    private static CancelRequest readCancelRequest(ByteBuffer payload) {
        ClientId clientId = readClientId(payload);
        return new CancelRequest(clientId, NonceSignature.of(readBytes(payload, NonceSignature.LENGTH)));
    }

    // ---- CANCEL_RESPONSE ----

    private static ByteBuffer writeCancelResponse(CancelResponse response) {
        ByteBuffer buffer = switch (response.status()) {
            case SUCCESS, NOT_REGISTERED -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH);
                b.put(response.status().code());
                yield b;
            }
            case INVALID_TOKEN -> {
                ByteBuffer b = ByteBuffer.allocate(STATUS_ONLY_PAYLOAD_LENGTH + Nonce.LENGTH);
                b.put(response.status().code());
                b.put(response.nonce().value());
                yield b;
            }
            case ALREADY_REGISTERED, CHALLENGE, CHALLENGE_REJECTED ->
                    throw new IllegalArgumentException("CANCEL_RESPONSE cannot carry " + response.status());
        };
        buffer.flip();
        return buffer;
    }

    private static CancelResponse readCancelResponse(ByteBuffer payload) {
        StatusCode status = readStatus(payload);
        return switch (status) {
            case SUCCESS -> CancelResponse.success();
            case NOT_REGISTERED -> CancelResponse.notRegistered();
            case INVALID_TOKEN -> CancelResponse.invalidToken(Nonce.of(readBytes(payload, Nonce.LENGTH)));
            case ALREADY_REGISTERED, CHALLENGE, CHALLENGE_REJECTED ->
                    throw new IllegalArgumentException("CANCEL_RESPONSE cannot carry " + status);
        };
    }

    // ---- shared helpers ----

    private static ClientId readClientId(ByteBuffer payload) {
        return ClientId.ofRawValue(payload.getLong());
    }

    private static StatusCode readStatus(ByteBuffer payload) {
        return StatusCode.fromCode(payload.get());
    }

    private static int readValidityPeriod(ByteBuffer payload) {
        return Short.toUnsignedInt(payload.getShort());
    }

    private static byte[] readBytes(ByteBuffer payload, int length) {
        byte[] bytes = new byte[length];
        payload.get(bytes);
        return bytes;
    }
}
