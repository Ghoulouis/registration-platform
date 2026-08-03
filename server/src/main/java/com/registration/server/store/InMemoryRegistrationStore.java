package com.registration.server.store;

import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

/**
 * PENDING/CONFIRMED lifecycle and Nonce rotation (ADR-0011), without Redis (ADR-0007).
 * {@code computeIfPresent} gives the same create-only/extend-only atomicity Redis's
 * {@code SET NX}/{@code SET XX} gave the Distributed Server — moot here in practice since the
 * NIO reactor is single-threaded (ADR-0001), but kept for interface robustness.
 *
 * <p>Expiry is driven per-record by a {@link HashedWheelTimer} rather than a full-table scan
 * (ADR-0016): every write schedules its own eviction and cancels whatever timeout previously
 * owned that Client ID. A stale timeout firing after its record has moved to a newer
 * generation is a no-op — eviction is a compare-and-remove against the exact {@link Record}
 * instance captured when the timeout was scheduled, never an unconditional removal.
 * {@link #reapExpired()} still runs, at a far lower frequency, purely as a defense-in-depth
 * safety net; {@link #get} also checks eagerly, so correctness never depends on either timing
 * mechanism.
 */
@Component
public class InMemoryRegistrationStore implements RegistrationStore {

    //private final Map<ClientId, Record> recordsByClientId = new ConcurrentHashMap<>();

    private final ConcurrentSkipListMap<ClientId, Record> recordsByClientId = new ConcurrentSkipListMap<>();

    private final Map<ClientId, Timeout> evictionsByClientId = new ConcurrentHashMap<>();
    private final Timer timer;

    public InMemoryRegistrationStore(
            @Value("${registration.timer-tick-duration-millis:1000}") long timerTickDurationMillis) {
        this.timer = new HashedWheelTimer(timerTickDurationMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        timer.stop();
    }

    @Override
    public ClientRecord get(ClientId clientId) {
        Record record = recordsByClientId.get(clientId);
        if (record == null || isExpired(record)) {
            return null;
        }
        return new ClientRecord(record.registered(), record.nonce(), record.previousNonce(), record.expiresAt());
    }

    @Override
    public Nonce issuePendingNonce(ClientId clientId, Duration nonceTtl) {
        Nonce nonce = Nonce.random();
        Instant nonceExpiresAt = Instant.now().plus(nonceTtl);
        Record record = new Record(false, nonce, null, null, nonceExpiresAt, null);
        recordsByClientId.put(clientId, record);
        scheduleEviction(clientId, record, nonceExpiresAt);
        return nonce;
    }

    @Override
    public boolean confirm(ClientId clientId, Duration validityPeriod, Nonce newNonce) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(validityPeriod);
        Record[] confirmedRecord = new Record[1];
        recordsByClientId.computeIfPresent(clientId, (id, current) -> {
            if (current.registered()) {
                return current; // already confirmed by a concurrent attempt; leave as-is
            }
            Record next = new Record(true, newNonce, null, expiresAt, null, now);
            confirmedRecord[0] = next;
            return next;
        });
        if (confirmedRecord[0] == null) {
            return false;
        }
        scheduleEviction(clientId, confirmedRecord[0], expiresAt);
        return true;
    }

    @Override
    public boolean rotateNonce(ClientId clientId, Duration validityPeriod, Nonce newNonce) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(validityPeriod);
        Record[] rotatedRecord = new Record[1];
        recordsByClientId.computeIfPresent(clientId, (id, current) -> {
            if (!current.registered()) {
                return current; // not actually confirmed; leave as-is
            }
            Record next = new Record(true, newNonce, current.nonce(), expiresAt, null, now);
            rotatedRecord[0] = next;
            return next;
        });
        if (rotatedRecord[0] == null) {
            return false;
        }
        scheduleEviction(clientId, rotatedRecord[0], expiresAt);
        return true;
    }

    @Override
    public boolean remove(ClientId clientId) {
        boolean removed = recordsByClientId.remove(clientId) != null;
        Timeout timeout = evictionsByClientId.remove(clientId);
        if (timeout != null) {
            timeout.cancel();
        }
        return removed;
    }

    @Override
    public List<RegisteredClient> listConfirmed(int page, int limit) {
        long skip = (long) page * limit;

        return recordsByClientId.entrySet().stream()
                // Lọc các record đã registered và chưa hết hạn
                .filter(entry -> entry.getValue().registered() && !isExpired(entry.getValue()))
                // Nhảy nhanh qua 'skip' phần tử thỏa mãn điều kiện
                .skip(skip)
                // Lấy tối đa 'limit' phần tử
                .limit(limit)
                // Biến đổi sang RegisteredClient object
                .map(entry -> new RegisteredClient(entry.getKey(), entry.getValue().expiresAt()))
                .toList();
    }

    @Override
    public long countConfirmed() {
        return recordsByClientId.values().stream()
                .filter(record -> record.registered() && !isExpired(record))
                .count();
    }

    @Scheduled(fixedDelayString = "${registration.reaper-interval-millis:300000}")
    void reapExpired() {
        recordsByClientId.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    /**
     * Schedules {@code record}'s own eviction at {@code expiresAt} and cancels whatever
     * timeout previously owned this Client ID. Cancellation here is best-effort housekeeping
     * only, never a correctness requirement: even if it's skipped or loses a race, a stale
     * timeout's own compare-and-remove makes firing late harmless (ADR-0016).
     */
    private void scheduleEviction(ClientId clientId, Record record, Instant expiresAt) {
        long delayMillis = Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis());
        Timeout timeout = timer.newTimeout(fired -> {
            recordsByClientId.remove(clientId, record);
            evictionsByClientId.remove(clientId, fired);
        }, delayMillis, TimeUnit.MILLISECONDS);
        Timeout previous = evictionsByClientId.put(clientId, timeout);
        if (previous != null) {
            previous.cancel();
        }
    }

    private static boolean isExpired(Record record) {
        return expiryOf(record).isBefore(Instant.now());
    }

    private static Instant expiryOf(Record record) {
        return record.registered() ? record.expiresAt() : record.nonceExpiresAt();
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
