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
 * PENDING/CONFIRMED lifecycle and Nonce rotation (ADR-0011), without Redis (ADR-0007).
 * {@code computeIfPresent} gives the same create-only/extend-only atomicity Redis's
 * {@code SET NX}/{@code SET XX} gave the Distributed Server — moot here in practice since the
 * NIO reactor is single-threaded (ADR-0001), but kept for interface robustness. There's no
 * TTL to lean on for expiry, so {@link #reapExpired()} — run on Spring's scheduler thread,
 * isolated from both the reactor thread and connection handling itself — periodically evicts
 * lapsed entries; {@link #get} also checks eagerly, so correctness never depends on the
 * reaper's timing.
 */
@Component
public class InMemoryRegistrationStore implements RegistrationStore {

    private final Map<ClientId, Record> recordsByClientId = new ConcurrentHashMap<>();

    @Override
    public ClientRecord get(ClientId clientId) {
        Record record = recordsByClientId.get(clientId);
        if (record == null || isExpired(record)) {
            return null;
        }
        return new ClientRecord(record.registered(), record.nonce(), record.previousNonce());
    }

    @Override
    public Nonce issuePendingNonce(ClientId clientId, Duration nonceTtl) {
        Nonce nonce = Nonce.random();
        Instant nonceExpiresAt = Instant.now().plus(nonceTtl);
        recordsByClientId.put(clientId, new Record(false, nonce, null, null, nonceExpiresAt, null));
        return nonce;
    }

    @Override
    public boolean confirm(ClientId clientId, Duration validityPeriod, Nonce newNonce) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(validityPeriod);
        boolean[] confirmed = {false};
        recordsByClientId.computeIfPresent(clientId, (id, current) -> {
            if (current.registered()) {
                return current; // already confirmed by a concurrent attempt; leave as-is
            }
            confirmed[0] = true;
            return new Record(true, newNonce, null, expiresAt, null, now);
        });
        return confirmed[0];
    }

    @Override
    public boolean rotateNonce(ClientId clientId, Duration validityPeriod, Nonce newNonce) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(validityPeriod);
        boolean[] rotated = {false};
        recordsByClientId.computeIfPresent(clientId, (id, current) -> {
            if (!current.registered()) {
                return current; // not actually confirmed; leave as-is
            }
            rotated[0] = true;
            return new Record(true, newNonce, current.nonce(), expiresAt, null, now);
        });
        return rotated[0];
    }

    @Override
    public boolean remove(ClientId clientId) {
        return recordsByClientId.remove(clientId) != null;
    }

    @Scheduled(fixedDelayString = "${registration.reaper-interval-millis}")
    void reapExpired() {
        recordsByClientId.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    private static boolean isExpired(Record record) {
        Instant now = Instant.now();
        return record.registered() ? record.expiresAt().isBefore(now) : record.nonceExpiresAt().isBefore(now);
    }

    private record Record(
            boolean registered,
            Nonce nonce,
            Nonce previousNonce,
            Instant expiresAt,
            Instant nonceExpiresAt,
            Instant lastRegisteredAt) {
    }
}
