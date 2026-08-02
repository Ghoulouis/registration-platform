package com.registration.server.store;

import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;

import java.time.Duration;

/**
 * Registration state held in local process memory (ADR-0007) — synchronous, unlike the
 * Distributed Server's reactive Redis-backed store, since there's no network I/O to await.
 */
public interface RegistrationStore {

    /** True if {@code clientId} has a live (unexpired) Registration. */
    boolean isRegistered(ClientId clientId);

    /** Creates a Registration valid for {@code validityPeriod} with {@code initialNonce}. False if one already exists. */
    boolean tryRegister(ClientId clientId, Duration validityPeriod, Nonce initialNonce);

    /** The live Registration's current and (if any) immediately-previous Nonce; {@code null} if none live (ADR-0010). */
    NonceState nonceState(ClientId clientId);

    /**
     * Extends the Registration by {@code validityPeriod} and rotates its Nonce: {@code newNonce}
     * becomes current, the old current becomes previous. False if none exists.
     */
    boolean rotateNonce(ClientId clientId, Duration validityPeriod, Nonce newNonce);

    /** Removes a Registration immediately (ADR-0004). False if none existed. */
    boolean cancel(ClientId clientId);

    record NonceState(Nonce current, Nonce previous) {
    }
}
