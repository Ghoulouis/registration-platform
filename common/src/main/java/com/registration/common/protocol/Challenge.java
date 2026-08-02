package com.registration.common.protocol;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A random nonce the Server issues for a Client to sign (ADR-0009). Wraps a fixed-length
 * byte array with value-based equality, same shape as {@link ClientId}.
 */
public final class Challenge {

    public static final int LENGTH = 32;

    private final byte[] value;

    private Challenge(byte[] value) {
        this.value = value;
    }

    public static Challenge of(byte[] value) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("Challenge must be " + LENGTH + " bytes, got: " + value.length);
        }
        return new Challenge(value.clone());
    }

    public static Challenge random() {
        byte[] bytes = new byte[LENGTH];
        ThreadLocalRandom.current().nextBytes(bytes);
        return new Challenge(bytes);
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Challenge other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "Challenge[" + HexFormat.of().formatHex(value) + "]";
    }
}
