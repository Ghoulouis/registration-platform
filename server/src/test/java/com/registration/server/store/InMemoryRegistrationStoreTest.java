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
    void registersNewClient() {
        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isTrue();
    }

    @Test
    void rejectsDuplicateRegister() {
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), Nonce.random());

        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isFalse();
    }

    @Test
    void nonceStateReflectsInitialNonceWithNoPrevious() {
        Nonce initialNonce = Nonce.random();
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), initialNonce);

        RegistrationStore.NonceState nonceState = store.nonceState(CLIENT_ID);

        assertThat(nonceState.current()).isEqualTo(initialNonce);
        assertThat(nonceState.previous()).isNull();
    }

    @Test
    void nonceStateIsNullForUnregisteredClient() {
        assertThat(store.nonceState(CLIENT_ID)).isNull();
    }

    @Test
    void rotateNonceMovesCurrentToPreviousAndSetsNewCurrent() {
        Nonce initialNonce = Nonce.random();
        Nonce rotatedNonce = Nonce.random();
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), initialNonce);

        assertThat(store.rotateNonce(CLIENT_ID, Duration.ofSeconds(60), rotatedNonce)).isTrue();

        RegistrationStore.NonceState nonceState = store.nonceState(CLIENT_ID);
        assertThat(nonceState.current()).isEqualTo(rotatedNonce);
        assertThat(nonceState.previous()).isEqualTo(initialNonce);
    }

    @Test
    void rejectsRotateOfUnregisteredClient() {
        assertThat(store.rotateNonce(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isFalse();
    }

    @Test
    void cancelsRegisteredClient() {
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), Nonce.random());

        assertThat(store.cancel(CLIENT_ID)).isTrue();
        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isTrue();
    }

    @Test
    void rejectsCancelOfUnregisteredClient() {
        assertThat(store.cancel(CLIENT_ID)).isFalse();
    }

    @Test
    void reaperEvictsExpiredRegistrations() throws InterruptedException {
        store.tryRegister(CLIENT_ID, Duration.ofMillis(1), Nonce.random());
        Thread.sleep(20);

        store.reapExpired();

        // If the entry were still present, this REGISTER would fail with ALREADY_REGISTERED-style rejection.
        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isTrue();
    }

    @Test
    void reaperLeavesLiveRegistrationsAlone() {
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), Nonce.random());

        store.reapExpired();

        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60), Nonce.random())).isFalse();
    }
}
