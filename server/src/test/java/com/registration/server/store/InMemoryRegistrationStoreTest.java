package com.registration.server.store;

import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRegistrationStoreTest {

    private static final ClientId CLIENT_ID = ClientId.parse("123456789012");

    private final InMemoryRegistrationStore store = new InMemoryRegistrationStore();

    @Test
    void getIsNullForAnUntouchedClientId() {
        assertThat(store.get(CLIENT_ID)).isNull();
    }

    @Test
    void issuePendingNonceCreatesAnUnregisteredRecord() {
        Nonce nonce = store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));

        RegistrationStore.ClientRecord record = store.get(CLIENT_ID);
        assertThat(record.registered()).isFalse();
        assertThat(record.nonce()).isEqualTo(nonce);
        assertThat(record.previousNonce()).isNull();
    }

    @Test
    void issuingAgainReplacesThePriorPendingNonce() {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));
        Nonce second = store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));

        assertThat(store.get(CLIENT_ID).nonce()).isEqualTo(second);
    }

    @Test
    void confirmTransitionsToRegisteredWithNoPreviousNonce() {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));
        Nonce confirmedNonce = Nonce.random();

        assertThat(store.confirm(CLIENT_ID, Duration.ofSeconds(60), confirmedNonce)).isTrue();

        RegistrationStore.ClientRecord record = store.get(CLIENT_ID);
        assertThat(record.registered()).isTrue();
        assertThat(record.nonce()).isEqualTo(confirmedNonce);
        assertThat(record.previousNonce()).isNull();
    }

    @Test
    void confirmFailsWithoutAPendingRecord() {
        assertThat(store.confirm(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isFalse();
    }

    @Test
    void confirmFailsIfAlreadyRegistered() {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));
        store.confirm(CLIENT_ID, Duration.ofSeconds(60), Nonce.random());

        assertThat(store.confirm(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isFalse();
    }

    @Test
    void rotateNonceMovesCurrentToPreviousAndSetsNewCurrent() {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));
        Nonce initialNonce = Nonce.random();
        store.confirm(CLIENT_ID, Duration.ofSeconds(60), initialNonce);
        Nonce rotatedNonce = Nonce.random();

        assertThat(store.rotateNonce(CLIENT_ID, Duration.ofSeconds(60), rotatedNonce)).isTrue();

        RegistrationStore.ClientRecord record = store.get(CLIENT_ID);
        assertThat(record.nonce()).isEqualTo(rotatedNonce);
        assertThat(record.previousNonce()).isEqualTo(initialNonce);
    }

    @Test
    void rejectsRotateOfUnregisteredClient() {
        assertThat(store.rotateNonce(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isFalse();
    }

    @Test
    void rejectsRotateOfPendingOnlyClient() {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));

        assertThat(store.rotateNonce(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isFalse();
    }

    @Test
    void removeDeletesARegisteredRecord() {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));
        store.confirm(CLIENT_ID, Duration.ofSeconds(60), Nonce.random());

        assertThat(store.remove(CLIENT_ID)).isTrue();
        assertThat(store.get(CLIENT_ID)).isNull();
    }

    @Test
    void removeDeletesAPendingRecord() {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));

        assertThat(store.remove(CLIENT_ID)).isTrue();
        assertThat(store.get(CLIENT_ID)).isNull();
    }

    @Test
    void removeOfUntouchedClientIdReturnsFalse() {
        assertThat(store.remove(CLIENT_ID)).isFalse();
    }

    @Test
    void reaperEvictsExpiredPendingNonce() throws InterruptedException {
        store.issuePendingNonce(CLIENT_ID, Duration.ofMillis(1));
        Thread.sleep(20);

        store.reapExpired();

        assertThat(store.get(CLIENT_ID)).isNull();
    }

    @Test
    void reaperEvictsExpiredRegistration() throws InterruptedException {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));
        store.confirm(CLIENT_ID, Duration.ofMillis(1), Nonce.random());
        Thread.sleep(20);

        store.reapExpired();

        assertThat(store.get(CLIENT_ID)).isNull();
    }

    @Test
    void reaperLeavesLiveRecordsAlone() {
        store.issuePendingNonce(CLIENT_ID, Duration.ofSeconds(30));
        store.confirm(CLIENT_ID, Duration.ofSeconds(60), Nonce.random());

        store.reapExpired();

        assertThat(store.get(CLIENT_ID)).isNotNull();
    }
}
