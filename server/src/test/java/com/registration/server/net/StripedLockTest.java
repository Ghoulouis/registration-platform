package com.registration.server.net;

import com.registration.common.protocol.ClientId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

class StripedLockTest {

    private final StripedLock locks = new StripedLock();

    @Test
    void sameClientIdAlwaysGetsTheSameLock() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(locks.forClientId(clientId)).isSameAs(locks.forClientId(clientId));
    }

    @Test
    void differentClientIdsAreNotAllForcedOntoOneLock() {
        ReentrantLock[] distinctLocks = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> ClientId.ofRawValue(i))
                .map(locks::forClientId)
                .distinct()
                .toArray(ReentrantLock[]::new);

        // 1000 distinct Client IDs should land on noticeably more than 1 stripe.
        assertThat(distinctLocks.length).isGreaterThan(1);
    }
}
