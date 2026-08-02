package com.registration.common.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageCodecTest {

    @Test
    void roundTripsRegisterRequest() {
        RegisterRequest original = new RegisterRequest(ClientId.parse("123456789012"));

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRenewRequest() {
        RenewRequest original = new RenewRequest(ClientId.parse("123456789012"));

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRegisterResponseSuccess() {
        RegisterResponse original = new RegisterResponse(StatusCode.SUCCESS, 300);

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRegisterResponseError() {
        RegisterResponse original = new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0);

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRenewResponse() {
        RenewResponse original = new RenewResponse(StatusCode.NOT_REGISTERED, 0);

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsCancelRequest() {
        CancelRequest original = new CancelRequest(ClientId.parse("123456789012"));

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsCancelResponseSuccess() {
        CancelResponse original = new CancelResponse(StatusCode.SUCCESS);

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsCancelResponseNotRegistered() {
        CancelResponse original = new CancelResponse(StatusCode.NOT_REGISTERED);

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void wireLayoutMatchesHeaderPlusPayload() {
        RegisterRequest message = new RegisterRequest(ClientId.ofRawValue(42L));

        ByteBuffer frame = MessageCodec.encode(message);

        assertEquals(MessageType.REGISTER_REQUEST.code(), frame.get(0));
        assertEquals(8, frame.getInt(1)); // payload length
        assertEquals(42L, frame.getLong(5));
        assertEquals(5 + 8, frame.remaining());
    }

    private static ByteBuffer encode(ProtocolMessage message) {
        return MessageCodec.encode(message);
    }

    private static ProtocolMessage decode(ByteBuffer frame) {
        MessageType type = MessageType.fromCode(frame.get());
        int length = frame.getInt();
        ByteBuffer payload = frame.slice();
        payload.limit(length);
        return MessageCodec.decode(type, payload);
    }
}
