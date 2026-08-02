package com.registration.service.store;

import com.registration.common.protocol.ClientId;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Registration existence and Expiration are both handled by Redis's own key TTL:
 * {@code SET NX EX} creates only if absent, {@code SET XX EX} extends only if present.
 * No separate expiry reaper is needed (ADR-0002).
 */
@Component
public class RedisRegistrationStore implements RegistrationStore {

    private static final String KEY_PREFIX = "registration:";
    private static final String MARKER_VALUE = "1";

    private final ReactiveStringRedisTemplate redisTemplate;

    public RedisRegistrationStore(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Boolean> tryRegister(ClientId clientId, Duration validityPeriod) {
        return redisTemplate.opsForValue().setIfAbsent(key(clientId), MARKER_VALUE, validityPeriod);
    }

    @Override
    public Mono<Boolean> renew(ClientId clientId, Duration validityPeriod) {
        return redisTemplate.opsForValue().setIfPresent(key(clientId), MARKER_VALUE, validityPeriod);
    }

    @Override
    public Mono<Boolean> cancel(ClientId clientId) {
        return redisTemplate.delete(key(clientId)).map(deletedCount -> deletedCount > 0);
    }

    private static String key(ClientId clientId) {
        return KEY_PREFIX + clientId;
    }
}
