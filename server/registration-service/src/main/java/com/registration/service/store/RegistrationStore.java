package com.registration.service.store;

import com.registration.common.protocol.ClientId;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Shared Registration state, backed by Redis so any Server pod can serve any Client (ADR-0002). */
public interface RegistrationStore {

    /** Creates a Registration valid for {@code validityPeriod}. False if one already exists. */
    Mono<Boolean> tryRegister(ClientId clientId, Duration validityPeriod);

    /** Extends an existing Registration by {@code validityPeriod}. False if none exists. */
    Mono<Boolean> renew(ClientId clientId, Duration validityPeriod);

    /** Removes a Registration immediately (ADR-0004). False if none existed. */
    Mono<Boolean> cancel(ClientId clientId);
}
