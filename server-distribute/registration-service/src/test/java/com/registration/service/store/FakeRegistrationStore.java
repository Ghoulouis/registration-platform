package com.registration.service.store;

import com.registration.common.protocol.ClientId;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;

public final class FakeRegistrationStore implements RegistrationStore {

    private final Set<ClientId> registered;

    public FakeRegistrationStore(Set<ClientId> registered) {
        this.registered = registered;
    }

    @Override
    public Mono<Boolean> tryRegister(ClientId clientId, Duration validityPeriod) {
        return Mono.just(registered.add(clientId));
    }

    @Override
    public Mono<Boolean> renew(ClientId clientId, Duration validityPeriod) {
        return Mono.just(registered.contains(clientId));
    }

    @Override
    public Mono<Boolean> cancel(ClientId clientId) {
        return Mono.just(registered.remove(clientId));
    }
}
