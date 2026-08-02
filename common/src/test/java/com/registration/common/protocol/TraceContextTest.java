package com.registration.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TraceContextTest {

    @Test
    void newSpanKeepsTheSameTraceIdButChangesTheSpanId() {
        TraceContext trace = TraceContext.newTrace();

        TraceContext nextSpan = trace.newSpan();

        assertArrayEquals(trace.traceId(), nextSpan.traceId());
        assertFalse(java.util.Arrays.equals(trace.spanId(), nextSpan.spanId()));
    }

    @Test
    void newTraceGeneratesDistinctTraceIds() {
        TraceContext first = TraceContext.newTrace();
        TraceContext second = TraceContext.newTrace();

        assertFalse(java.util.Arrays.equals(first.traceId(), second.traceId()));
    }

    @Test
    void toStringMatchesTheW3CTraceparentShape() {
        TraceContext trace = TraceContext.of(
                new byte[TraceContext.TRACE_ID_LENGTH], new byte[TraceContext.SPAN_ID_LENGTH], (byte) 0x01);

        assertEquals("00-00000000000000000000000000000000-0000000000000000-01", trace.toString());
    }
}
