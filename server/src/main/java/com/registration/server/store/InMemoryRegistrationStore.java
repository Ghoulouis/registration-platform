package com.registration.server.store;

import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registration existence, Nonce rotation (ADR-0010), and Expiration, without Redis
 * (ADR-0007). {@code putIfAbsent} and {@code computeIfPresent} give the same
 * create-only/extend-only atomicity Redis's {@code SET NX}/{@code SET XX} gave the
 * Distributed Server. There's no TTL to lean on for Expiration, so {@link #reapExpired()}
 * — run on Spring's scheduler thread, isolated from both the NIO reactor thread and the
 * connection handling itself — periodically evicts lapsed entries.
 */
@Component
public class InMemoryRegistrationStore implements RegistrationStore {

    private final Map<ClientId, Record> recordsByClientId = new ConcurrentHashMap<>();

    @Override
    public boolean isRegistered(ClientId clientId) {
        Record record = recordsByClientId.get(clientId);
        return record != null && record.expiresAt().isAfter(Instant.now());
    }

    @Override
    public boolean tryRegister(ClientId clientId, Duration validityPeriod, Nonce initialNonce) {
        Instant expiresAt = Instant.now().plus(validityPeriod);
        return recordsByClientId.putIfAbsent(clientId, new Record(expiresAt, initialNonce, null)) == null;
    }

    @Override
    public NonceState nonceState(ClientId clientId) {
        Record record = recordsByClientId.get(clientId);
        if (record == null || record.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        return new NonceState(record.currentNonce(), record.previousNonce());
    }

    @Override
    public boolean rotateNonce(ClientId clientId, Duration validityPeriod, Nonce newNonce) {
        Instant expiresAt = Instant.now().plus(validityPeriod);
        return recordsByClientId.computeIfPresent(clientId,
                (id, current) -> new Record(expiresAt, newNonce, current.currentNonce())) != null;
    }

    @Override
    public boolean cancel(ClientId clientId) {
        return recordsByClientId.remove(clientId) != null;
    }

    @Scheduled(fixedDelayString = "${registration.reaper-interval-millis}")
    void reapExpired() {
        Instant now = Instant.now();
        recordsByClientId.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record Record(Instant expiresAt, Nonce currentNonce, Nonce previousNonce) {
    }
}
