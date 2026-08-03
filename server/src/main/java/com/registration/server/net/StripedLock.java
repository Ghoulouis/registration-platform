package com.registration.server.net;

import com.registration.common.protocol.ClientId;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A fixed pool of locks (ADR-0015), one Client ID hashed to one of them — not an unbounded
 * {@code Map<ClientId, Lock>}, which would grow forever as new Client IDs appear over a
 * long-running server's lifetime (CONTEXT.md targets up to a million Clients). Different
 * Client IDs occasionally sharing a stripe is harmless: each Client has at most one request
 * in flight at a time in practice, so real contention on a shared stripe is rare.
 */
final class StripedLock {

    private static final int STRIPE_COUNT = 256;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

    StripedLock() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    ReentrantLock forClientId(ClientId clientId) {
        int index = Math.floorMod(clientId.hashCode(), STRIPE_COUNT);
        return locks[index];
    }
}
