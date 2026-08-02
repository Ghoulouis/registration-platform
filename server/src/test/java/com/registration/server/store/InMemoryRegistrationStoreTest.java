package com.registration.server.store;

import com.registration.common.protocol.ClientId;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRegistrationStoreTest {

    private static final ClientId CLIENT_ID = ClientId.parse("123456789012");

    private final InMemoryRegistrationStore store = new InMemoryRegistrationStore();

    @Test
    void registersNewClient() {
        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void rejectsDuplicateRegister() {
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60));

        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void renewsRegisteredClient() {
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60));

        assertThat(store.renew(CLIENT_ID, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void rejectsRenewOfUnregisteredClient() {
        assertThat(store.renew(CLIENT_ID, Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void cancelsRegisteredClient() {
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60));

        assertThat(store.cancel(CLIENT_ID)).isTrue();
        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void rejectsCancelOfUnregisteredClient() {
        assertThat(store.cancel(CLIENT_ID)).isFalse();
    }

    @Test
    void reaperEvictsExpiredRegistrations() throws InterruptedException {
        store.tryRegister(CLIENT_ID, Duration.ofMillis(1));
        Thread.sleep(20);

        store.reapExpired();

        // If the entry were still present, this REGISTER would fail with ALREADY_REGISTERED-style rejection.
        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void reaperLeavesLiveRegistrationsAlone() {
        store.tryRegister(CLIENT_ID, Duration.ofSeconds(60));

        store.reapExpired();

        assertThat(store.tryRegister(CLIENT_ID, Duration.ofSeconds(60))).isFalse();
    }
}
