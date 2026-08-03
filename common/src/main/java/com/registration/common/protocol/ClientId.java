package com.registration.common.protocol;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A Client-chosen identifier: 12 decimal digits at the domain level, packed as a
 * long on the wire (see ADR-0003). {@link #toString()} zero-pads back to 12 digits,
 * so no information is lost round-tripping through {@link #rawValue()}.
 */
public final class ClientId implements Comparable<ClientId> {

    private static final int DIGIT_COUNT = 12;
    private static final long MAX_VALUE = 999_999_999_999L;

    private final long value;

    private ClientId(long value) {
        this.value = value;
    }

    public static ClientId parse(String id) {
        Objects.requireNonNull(id, "id");
        if (id.length() != DIGIT_COUNT || !id.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "Client ID must be a " + DIGIT_COUNT + "-digit numeric string, got: " + id);
        }
        return new ClientId(Long.parseLong(id));
    }

    public static ClientId ofRawValue(long value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException("Client ID out of range: " + value);
        }
        return new ClientId(value);
    }

    /** A Client choosing its own ID (CONTEXT.md) — freshly generated, never persisted. */
    public static ClientId random() {
        return new ClientId(ThreadLocalRandom.current().nextLong(MAX_VALUE + 1));
    }

    public long rawValue() {
        return value;
    }

    @Override
    public String toString() {
        return String.format("%0" + DIGIT_COUNT + "d", value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClientId other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public int compareTo(ClientId o) {
        return Long.compare(this.value, o.rawValue());
    }
}
