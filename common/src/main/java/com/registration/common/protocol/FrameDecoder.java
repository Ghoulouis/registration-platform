package com.registration.common.protocol;

import java.nio.ByteBuffer;

/**
 * Accumulates bytes fed from repeated non-blocking channel reads until one
 * complete frame is available, then decodes it. Each connection carries exactly
 * one message per direction (ADR-0001/0003), so one instance handles exactly
 * one frame — create a fresh decoder per connection.
 */
public final class FrameDecoder {

    private static final int MAX_PAYLOAD_LENGTH = 256;

    private final ByteBuffer header = ByteBuffer.allocate(MessageCodec.HEADER_LENGTH);
    private ByteBuffer payload;
    private MessageType type;

    /** Consumes as many bytes from {@code input} as are needed to complete the frame. */
    public void feed(ByteBuffer input) {
        if (payload == null) {
            transfer(input, header);
            if (!header.hasRemaining()) {
                header.flip();
                type = MessageType.fromCode(header.get());
                int payloadLength = header.getInt();
                if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
                    throw new IllegalStateException("Invalid frame payload length: " + payloadLength);
                }
                payload = ByteBuffer.allocate(payloadLength);
            }
        }
        if (payload != null) {
            transfer(input, payload);
        }
    }

    public boolean isComplete() {
        return payload != null && !payload.hasRemaining();
    }

    public ProtocolMessage decode() {
        if (!isComplete()) {
            throw new IllegalStateException("Frame is not fully read yet");
        }
        payload.flip();
        return MessageCodec.decode(type, payload);
    }

    private static void transfer(ByteBuffer source, ByteBuffer target) {
        int count = Math.min(source.remaining(), target.remaining());
        int originalLimit = source.limit();
        source.limit(source.position() + count);
        target.put(source);
        source.limit(originalLimit);
    }
}
