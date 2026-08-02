package com.registration.server.store;

import com.registration.common.protocol.ClientId;

import java.time.Duration;

/**
 * Registration state held in local process memory (ADR-0007) — synchronous, unlike the
 * Distributed Server's reactive Redis-backed store, since there's no network I/O to await.
 */
public interface RegistrationStore {

    /** True if {@code clientId} has a live (unexpired) Registration. */
    boolean isRegistered(ClientId clientId);

    /** Creates a Registration valid for {@code validityPeriod}. False if one already exists. */
    boolean tryRegister(ClientId clientId, Duration validityPeriod);

    /** Extends an existing Registration by {@code validityPeriod}. False if none exists. */
    boolean renew(ClientId clientId, Duration validityPeriod);

    /** Removes a Registration immediately (ADR-0004). False if none existed. */
    boolean cancel(ClientId clientId);
}
