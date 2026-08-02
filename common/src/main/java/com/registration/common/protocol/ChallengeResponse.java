package com.registration.common.protocol;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * The Ed25519 signature a Client computes over a {@link Challenge} (ADR-0009). Wraps a
 * fixed-length byte array with value-based equality, same shape as {@link ClientId}.
 */
public final class ChallengeResponse {

    public static final int LENGTH = 64;

    private final byte[] value;

    private ChallengeResponse(byte[] value) {
        this.value = value;
    }

    public static ChallengeResponse of(byte[] value) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("Challenge Response must be " + LENGTH + " bytes, got: " + value.length);
        }
        return new ChallengeResponse(value.clone());
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChallengeResponse other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "ChallengeResponse[" + HexFormat.of().formatHex(value) + "]";
    }
}
