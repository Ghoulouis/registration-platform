package com.registration.common.protocol;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * The Ed25519 signature a Client computes over a {@link Nonce} to authenticate a Renewal
 * or Cancellation (ADR-0010). Wraps a fixed-length byte array with value-based equality,
 * same shape as {@link ClientId}.
 */
public final class NonceSignature {

    public static final int LENGTH = 64;

    private final byte[] value;

    private NonceSignature(byte[] value) {
        this.value = value;
    }

    public static NonceSignature of(byte[] value) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("Nonce Signature must be " + LENGTH + " bytes, got: " + value.length);
        }
        return new NonceSignature(value.clone());
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NonceSignature other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "NonceSignature[" + HexFormat.of().formatHex(value) + "]";
    }
}
