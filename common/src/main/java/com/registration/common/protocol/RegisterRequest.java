package com.registration.common.protocol;

import java.util.Objects;

/**
 * Client -> Server: register {@code clientId}. Fails if one already exists. Two shapes
 * (ADR-0009): the initial attempt carries no {@code challengeResponse} and gets back a
 * Challenge; resubmitting with a {@code challengeResponse} completes the Registration.
 */
public record RegisterRequest(ClientId clientId, ChallengeResponse challengeResponse) implements ProtocolMessage {

    public static RegisterRequest initial(ClientId clientId) {
        return new RegisterRequest(clientId, null);
    }

    public static RegisterRequest withChallengeResponse(ClientId clientId, ChallengeResponse challengeResponse) {
        return new RegisterRequest(clientId, Objects.requireNonNull(challengeResponse));
    }

    public boolean hasChallengeResponse() {
        return challengeResponse != null;
    }

    @Override
    public MessageType type() {
        return MessageType.REGISTER_REQUEST;
    }
}
