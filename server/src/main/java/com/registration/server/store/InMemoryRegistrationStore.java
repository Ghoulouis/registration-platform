package com.registration.server.store;

import com.registration.common.protocol.ClientId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registration existence and Expiration, without Redis (ADR-0007). {@code putIfAbsent}
 * and {@code computeIfPresent} give the same create-only/extend-only atomicity Redis's
 * {@code SET NX}/{@code SET XX} gave the Distributed Server. There's no TTL to lean on
 * for Expiration, so {@link #reapExpired()} — run on Spring's scheduler thread, isolated
 * from both the NIO reactor thread and the connection handling itself — periodically
 * evicts lapsed entries.
 */
@Component
public class InMemoryRegistrationStore implements RegistrationStore {

    private final Map<ClientId, Instant> expiryByClientId = new ConcurrentHashMap<>();

    @Override
    public boolean tryRegister(ClientId clientId, Duration validityPeriod) {
        Instant expiresAt = Instant.now().plus(validityPeriod);
        return expiryByClientId.putIfAbsent(clientId, expiresAt) == null;
    }

    @Override
    public boolean renew(ClientId clientId, Duration validityPeriod) {
        Instant expiresAt = Instant.now().plus(validityPeriod);
        return expiryByClientId.computeIfPresent(clientId, (id, currentExpiry) -> expiresAt) != null;
    }

    @Override
    public boolean cancel(ClientId clientId) {
        return expiryByClientId.remove(clientId) != null;
    }

    @Scheduled(fixedDelayString = "${registration.reaper-interval-millis}")
    void reapExpired() {
        Instant now = Instant.now();
        expiryByClientId.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }
}
