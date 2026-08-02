package com.registration.common.protocol;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C Trace Context's core {@code traceparent} fields (ADR-0012) — no {@code tracestate},
 * no multi-vendor propagation need in this two-party system. Trace ID identifies one
 * logical Register/Renewal/Cancellation attempt; Span ID identifies one connection attempt
 * within it (a retry, or one of Register's two legs) and is not secret, so plain
 * {@link ThreadLocalRandom} is fine — unlike {@link Nonce} and {@link Challenge}, this isn't
 * a credential.
 */
public final class TraceContext {

    public static final int TRACE_ID_LENGTH = 16;
    public static final int SPAN_ID_LENGTH = 8;
    private static final byte VERSION = 0x00;
    private static final byte SAMPLED = 0x01;

    private final byte[] traceId;
    private final byte[] spanId;
    private final byte flags;

    private TraceContext(byte[] traceId, byte[] spanId, byte flags) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.flags = flags;
    }

    /** Starts a new Trace: fresh Trace ID, and a fresh Span ID for the first attempt. */
    public static TraceContext newTrace() {
        return new TraceContext(randomBytes(TRACE_ID_LENGTH), randomBytes(SPAN_ID_LENGTH), SAMPLED);
    }

    public static TraceContext of(byte[] traceId, byte[] spanId, byte flags) {
        if (traceId.length != TRACE_ID_LENGTH) {
            throw new IllegalArgumentException("Trace ID must be " + TRACE_ID_LENGTH + " bytes, got: " + traceId.length);
        }
        if (spanId.length != SPAN_ID_LENGTH) {
            throw new IllegalArgumentException("Span ID must be " + SPAN_ID_LENGTH + " bytes, got: " + spanId.length);
        }
        return new TraceContext(traceId.clone(), spanId.clone(), flags);
    }

    /** A fresh Span ID for another connection attempt within the same Trace (ADR-0012). */
    public TraceContext newSpan() {
        return new TraceContext(traceId, randomBytes(SPAN_ID_LENGTH), flags);
    }

    public byte[] traceId() {
        return traceId.clone();
    }

    public byte[] spanId() {
        return spanId.clone();
    }

    public byte flags() {
        return flags;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TraceContext other
                && Arrays.equals(traceId, other.traceId)
                && Arrays.equals(spanId, other.spanId)
                && flags == other.flags;
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(traceId) + Arrays.hashCode(spanId);
    }

    /** The standard {@code traceparent} string form, e.g. {@code 00-4bf92f...-00f067...-01}. */
    @Override
    public String toString() {
        HexFormat hex = HexFormat.of();
        return "%02x-%s-%s-%02x".formatted(VERSION, hex.formatHex(traceId), hex.formatHex(spanId), flags);
    }
}
