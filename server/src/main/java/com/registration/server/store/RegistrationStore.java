package com.registration.server.store;

import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One record per Client ID, spanning both lifecycle phases (ADR-0011): PENDING (a Nonce was
 * issued for an unconfirmed Register attempt) and CONFIRMED (a live Registration). Held in
 * local process memory (ADR-0007) — synchronous, unlike the Distributed Server's reactive
 * Redis-backed store, since there's no network I/O to await.
 */
public interface RegistrationStore {

    /**
     * The live record for {@code clientId} — PENDING or CONFIRMED — or {@code null} if neither
     * exists or it has expired (checked eagerly here, not dependent on the reaper's timing).
     */
    ClientRecord get(ClientId clientId);

    /** Issues a fresh pending Nonce (Register Step 1), replacing any prior pending or expired state. */
    Nonce issuePendingNonce(ClientId clientId, Duration nonceTtl);

    /**
     * Confirms {@code clientId} as registered, using {@code newNonce} as its first current
     * Nonce (no previous). False if it lost the race to a concurrent successful attempt for
     * the same Client ID.
     */
    boolean confirm(ClientId clientId, Duration validityPeriod, Nonce newNonce);

    /**
     * Extends an already-confirmed Registration by {@code validityPeriod} and rotates its
     * Nonce: {@code newNonce} becomes current, the old current becomes previous. False if not
     * currently confirmed.
     */
    boolean rotateNonce(ClientId clientId, Duration validityPeriod, Nonce newNonce);

    /**
     * Removes the record entirely, PENDING or CONFIRMED — used both for a voluntary
     * Cancellation (ADR-0004) and to discard a spent pending Nonce after a failed
     * verification attempt, since a pending Nonce is single-use either way (ADR-0009).
     * False if none existed.
     */
    boolean remove(ClientId clientId);

    /**
     * A page of live CONFIRMED Registrations (ADR-0017) — best-effort, not a stable listing:
     * paged directly over the store's current iteration order with no sort and no snapshot, so
     * a concurrent Register/Renew/Cancel/Expiration during pagination can skip or duplicate
     * entries across pages. Not for anything requiring an exhaustive or reproducible result.
     *
     * @param page zero-based page index
     * @param limit max entries to return
     */
    List<RegisteredClient> listConfirmed(int page, int limit);

    /** Count of currently live CONFIRMED Registrations, subject to the same best-effort caveat as {@link #listConfirmed}. */
    long countConfirmed();

    /**
     * @param registered PENDING (false) vs CONFIRMED (true) — not "currently live"; liveness is
     *                    the store's job to check before ever returning a record (ADR-0011).
     * @param previousNonce only ever non-null once CONFIRMED and rotated at least once.
     * @param expiresAt the Registration's actual expiry; only meaningful once {@code registered}
     *                  — {@code null} for a PENDING-only record.
     */
    record ClientRecord(boolean registered, Nonce nonce, Nonce previousNonce, Instant expiresAt) {
    }

    /** One entry in {@link #listConfirmed} — deliberately excludes the Nonce (ADR-0017). */
    record RegisteredClient(ClientId clientId, Instant expiresAt) {
    }
}
