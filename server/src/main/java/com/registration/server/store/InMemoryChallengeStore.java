package com.registration.server.store;

import com.registration.common.protocol.Challenge;
import com.registration.common.protocol.ClientId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Same reaper shape as {@link InMemoryRegistrationStore} for Challenges that are issued
 * but never followed up on (ADR-0009) — {@link #consume} already checks expiry inline, so
 * {@link #reapExpired()} only bounds memory for abandoned entries, not correctness.
 */
@Component
public class InMemoryChallengeStore implements ChallengeStore {

    private final Map<ClientId, Entry> entriesByClientId = new ConcurrentHashMap<>();

    @Override
    public Challenge issue(ClientId clientId, Duration ttl) {
        Challenge challenge = Challenge.random();
        entriesByClientId.put(clientId, new Entry(challenge, Instant.now().plus(ttl)));
        return challenge;
    }

    @Override
    public Challenge consume(ClientId clientId) {
        Entry entry = entriesByClientId.remove(clientId);
        if (entry == null || entry.expiresAt.isBefore(Instant.now())) {
            return null;
        }
        return entry.challenge;
    }

    @Scheduled(fixedDelayString = "${registration.reaper-interval-millis}")
    void reapExpired() {
        Instant now = Instant.now();
        entriesByClientId.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    private record Entry(Challenge challenge, Instant expiresAt) {
    }
}
