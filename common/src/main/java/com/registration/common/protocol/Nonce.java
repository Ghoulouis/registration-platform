package com.registration.common.protocol;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A random value the Server holds for a live Registration, distinct from {@link Challenge}
 * (ADR-0010): issued when Register succeeds, replaced on every successful Renewal, discarded
 * on Cancellation or Expiration. Wraps a fixed-length byte array with value-based equality,
 * same shape as {@link ClientId}.
 */
public final class Nonce {

    public static final int LENGTH = 32;

    private final byte[] value;

    private Nonce(byte[] value) {
        this.value = value;
    }

    public static Nonce of(byte[] value) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("Nonce must be " + LENGTH + " bytes, got: " + value.length);
        }
        return new Nonce(value.clone());
    }

    public static Nonce random() {
        byte[] bytes = new byte[LENGTH];
        ThreadLocalRandom.current().nextBytes(bytes);
        return new Nonce(bytes);
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Nonce other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "Nonce[" + HexFormat.of().formatHex(value) + "]";
    }
}
