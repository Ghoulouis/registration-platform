package com.registration.common.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameDecoderTest {

    @Test
    void decodesFrameDeliveredInOneChunk() {
        ProtocolMessage original = new RegisterRequest(ClientId.parse("123456789012"));
        ByteBuffer frame = MessageCodec.encode(original);

        FrameDecoder decoder = new FrameDecoder();
        decoder.feed(frame);

        assertTrue(decoder.isComplete());
        assertEquals(original, decoder.decode());
    }

    @Test
    void decodesFrameDeliveredOneByteAtATime() {
        ProtocolMessage original = new RenewResponse(StatusCode.SUCCESS, 120);
        ByteBuffer frame = MessageCodec.encode(original);

        FrameDecoder decoder = new FrameDecoder();
        while (frame.hasRemaining()) {
            ByteBuffer singleByte = ByteBuffer.allocate(1).put(frame.get()).flip();
            assertFalse(decoder.isComplete());
            decoder.feed(singleByte);
        }

        assertTrue(decoder.isComplete());
        assertEquals(original, decoder.decode());
    }

    @Test
    void rejectsFrameWithOversizedPayloadLength() {
        ByteBuffer badHeader = ByteBuffer.allocate(5)
                .put(MessageType.REGISTER_REQUEST.code())
                .putInt(Integer.MAX_VALUE)
                .flip();

        FrameDecoder decoder = new FrameDecoder();

        assertThrows(IllegalStateException.class, () -> decoder.feed(badHeader));
    }
}
