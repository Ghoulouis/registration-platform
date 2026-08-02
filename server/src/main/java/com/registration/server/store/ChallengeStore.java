package com.registration.server.store;

import com.registration.common.protocol.Challenge;
import com.registration.common.protocol.ClientId;

import java.time.Duration;

/** Issued-but-not-yet-verified Challenges, held in local process memory (ADR-0009). */
public interface ChallengeStore {

    /** Issues a fresh Challenge for {@code clientId}, replacing any prior unconsumed one. */
    Challenge issue(ClientId clientId, Duration ttl);

    /**
     * Removes and returns the live Challenge for {@code clientId}, or {@code null} if none
     * exists or it expired. Consumed on any call, regardless of what the caller does with the
     * result — a Challenge is usable for at most one verification attempt (ADR-0009).
     */
    Challenge consume(ClientId clientId);
}
