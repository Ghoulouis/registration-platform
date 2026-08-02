package com.registration.common.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageCodecTest {

    @Test
    void roundTripsInitialRegisterRequest() {
        RegisterRequest original = RegisterRequest.initial(ClientId.parse("123456789012"), TraceContext.newTrace());

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRegisterRequestWithNonceSignature() {
        NonceSignature signature = NonceSignature.of(new byte[NonceSignature.LENGTH]);
        RegisterRequest original =
                RegisterRequest.withNonceSignature(ClientId.parse("123456789012"), TraceContext.newTrace(), signature);

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRegisterResponseSuccess() {
        RegisterResponse original = RegisterResponse.success(300, Nonce.random());

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRegisterResponseAlreadyRegistered() {
        RegisterResponse original = RegisterResponse.alreadyRegistered(Nonce.random());

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRegisterResponseChallenge() {
        RegisterResponse original = RegisterResponse.challenge(Nonce.random());

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRegisterResponseChallengeRejected() {
        RegisterResponse original = RegisterResponse.challengeRejected();

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRenewRequest() {
        NonceSignature signature = NonceSignature.of(new byte[NonceSignature.LENGTH]);
        RenewRequest original = new RenewRequest(ClientId.parse("123456789012"), TraceContext.newTrace(), signature);

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRenewResponseSuccess() {
        RenewResponse original = RenewResponse.success(60, Nonce.random());

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRenewResponseNotRegistered() {
        RenewResponse original = RenewResponse.notRegistered();

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsRenewResponseInvalidToken() {
        RenewResponse original = RenewResponse.invalidToken(Nonce.random());

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsCancelRequest() {
        NonceSignature signature = NonceSignature.of(new byte[NonceSignature.LENGTH]);
        CancelRequest original = new CancelRequest(ClientId.parse("123456789012"), TraceContext.newTrace(), signature);

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsCancelResponseSuccess() {
        CancelResponse original = CancelResponse.success();

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsCancelResponseNotRegistered() {
        CancelResponse original = CancelResponse.notRegistered();

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void roundTripsCancelResponseInvalidToken() {
        CancelResponse original = CancelResponse.invalidToken(Nonce.random());

        assertEquals(original, decode(encode(original)));
    }

    @Test
    void wireLayoutMatchesHeaderPlusPayload() {
        RegisterRequest message = RegisterRequest.initial(ClientId.ofRawValue(42L), TraceContext.newTrace());

        ByteBuffer frame = MessageCodec.encode(message);

        int expectedPayloadLength = 8 + 26; // Client ID + Trace Context (version+traceId+spanId+flags)
        assertEquals(MessageType.REGISTER_REQUEST.code(), frame.get(0));
        assertEquals(expectedPayloadLength, frame.getInt(1)); // payload length
        assertEquals(42L, frame.getLong(5));
        assertEquals(5 + expectedPayloadLength, frame.remaining());
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
