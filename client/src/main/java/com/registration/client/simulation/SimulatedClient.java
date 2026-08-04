package com.registration.client.simulation;

import com.registration.client.retry.CallFailedException;
import com.registration.client.retry.RetryingRequester;
import com.registration.common.crypto.Ed25519;
import com.registration.common.observability.RegistrationEventLog;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.NonceSignature;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.common.protocol.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.security.PrivateKey;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One Simulated Client's full lifecycle (CONTEXT.md): REGISTER, then RENEW on a
 * randomized schedule (ADR-0006's Renewal Window) until interrupted, then a
 * best-effort voluntary CANCEL (ADR-0004). Runs entirely on the virtual thread that
 * invokes {@link #run()} — no shared mutable state with other Simulated Clients.
 *
 * <p>Owns the Trace for each business transaction (ADR-0012 revision): a Trace represents
 * one REGISTER/RENEW/CANCEL as decided here, not inside {@link RetryingRequester}, which is
 * generic transport/retry machinery with no business of deciding when a transaction begins.
 * Each of {@link #register}/{@link #renew}/{@link #cancel} mints its own Trace, holds it in
 * MDC for the transaction's full duration — including its own outcome log line — and clears
 * it before returning.
 */
public final class SimulatedClient implements Runnable {

    private final ClientId clientId;
    private final RetryingRequester requester;
    private final PrivateKey signingKey;
    private final int assumedValidityPeriodSeconds;
    private final int renewalWindowMinPercent;
    private final int renewalWindowMaxPercent;

    // Set from Register's response, updated after every successful Renewal (ADR-0010).
    // Touched only by this Simulated Client's own thread - no synchronization needed.
    private Nonce currentNonce;

    // The OTel Context scope opened by putTraceContext, closed by clearTraceContext (ADR-0018)
    // - one push/pop per transaction, matching MDC's own put/remove bracket exactly.
    private Scope traceScope;

    public SimulatedClient(
            ClientId clientId,
            RetryingRequester requester,
            PrivateKey signingKey,
            int assumedValidityPeriodSeconds,
            int renewalWindowMinPercent,
            int renewalWindowMaxPercent) {
        this.clientId = clientId;
        this.requester = requester;
        this.signingKey = signingKey;
        this.assumedValidityPeriodSeconds = assumedValidityPeriodSeconds;
        this.renewalWindowMinPercent = renewalWindowMinPercent;
        this.renewalWindowMaxPercent = renewalWindowMaxPercent;
    }

    @Override
    public void run() {
        int validityPeriodSeconds;
        try {
            validityPeriodSeconds = register();
        } catch (CallFailedException e) {
            return; // already logged inside register(), with its Trace still in scope
        } catch (InterruptedException e) {
            return; // shut down before ever registering; nothing to cancel
        }

        try {
            while (true) {
                Thread.sleep(renewalDelayMillis(validityPeriodSeconds));
                validityPeriodSeconds = renew();
            }
        } catch (InterruptedException e) {
            // shutdown signal: fall through to voluntary Cancellation
        } catch (RenewalFailedException e) {
            // already logged inside renew(); not confident we're still registered, don't cancel
            return;
        }

        cancel();
    }

    private int register() throws InterruptedException {
        TraceContext trace = TraceContext.newTrace();
        putTraceContext(trace);
        try {
            RegisterResponse response = requester.register(clientId, signingKey, trace);
            if (response.status() == StatusCode.SUCCESS) {
                currentNonce = response.nonce();
                RegistrationEventLog.log(clientId, "REGISTER", "register_success", RegistrationEventLog.Level.INFO);
                return response.validityPeriodSeconds();
            }
            if (response.status() == StatusCode.ALREADY_REGISTERED) {
                currentNonce = response.nonce();
                RegistrationEventLog.log(clientId, "REGISTER", "already_registered", RegistrationEventLog.Level.INFO);
                return assumedValidityPeriodSeconds;
            }
            throw new CallFailedException("REGISTER challenge rejected");
        } catch (CallFailedException e) {
            RegistrationEventLog.log(clientId, "REGISTER", "call_failed", RegistrationEventLog.Level.WARN);
            throw e;
        } finally {
            clearTraceContext();
        }
    }

    private int renew() throws InterruptedException {
        TraceContext trace = TraceContext.newTrace();
        putTraceContext(trace);
        try {
            NonceSignature signature = Ed25519.sign(signingKey, currentNonce);
            RenewResponse response;
            try {
                RegistrationEventLog.log(clientId, "RENEW", "send_renew_request", RegistrationEventLog.Level.DEBUG);
                response = requester.renew(clientId, signature, trace);
            } catch (CallFailedException e) {
                throw new RenewalFailedException(e.getMessage());
            }
            if (response.status() != StatusCode.SUCCESS) {
                throw new RenewalFailedException("Server returned " + response.status());
            }
            currentNonce = response.nonce();
            RegistrationEventLog.log(clientId, "RENEW", "renew_success", RegistrationEventLog.Level.INFO);
            return response.validityPeriodSeconds();
        } catch (RenewalFailedException e) {
            RegistrationEventLog.log(clientId, "RENEW", "call_failed", RegistrationEventLog.Level.WARN);
            throw e;
        } finally {
            clearTraceContext();
        }
    }

    private void cancel() {
        TraceContext trace = TraceContext.newTrace();
        putTraceContext(trace);
        try {
            NonceSignature signature = Ed25519.sign(signingKey, currentNonce);
            CancelResponse response = requester.cancel(clientId, signature, trace);

            if (response.status() == StatusCode.SUCCESS) {
                RegistrationEventLog.log( clientId, "CANCEL", "cancel_success", RegistrationEventLog.Level.INFO);
            } else {
                RegistrationEventLog.log( clientId, "CANCEL", "cancel_failed", RegistrationEventLog.Level.WARN);
            }

        } catch (CallFailedException e) {
            RegistrationEventLog.log(clientId, "CANCEL", "call_failed", RegistrationEventLog.Level.WARN);
        } catch (InterruptedException e) {
            // already shutting down; nothing more to do
        } finally {
            clearTraceContext();
        }
    }

    private long renewalDelayMillis(int validityPeriodSeconds) {
        double minFraction = renewalWindowMinPercent / 100.0;
        double maxFraction = renewalWindowMaxPercent / 100.0;
        double fraction = ThreadLocalRandom.current().nextDouble(minFraction, maxFraction);
        return (long) (validityPeriodSeconds * 1000L * fraction);
    }

    private void putTraceContext(TraceContext trace) {
        HexFormat hex = HexFormat.of();
        String traceId = hex.formatHex(trace.traceId());
        String spanId = hex.formatHex(trace.spanId());
        MDC.put("traceId", traceId);
        MDC.put("spanId", spanId);

        // Wraps ADR-0012's own Trace ID/Span ID as a real (non-recording) OTel Span so the
        // Log Data Model's trace_id/span_id fields populate via the SDK's normal mechanism
        // (ADR-0018) - no span is ever recorded or exported, this is log correlation only.
        SpanContext spanContext = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        traceScope = Span.wrap(spanContext).storeInContext(Context.current()).makeCurrent();
    }

    private void clearTraceContext() {
        traceScope.close();
        traceScope = null;
        MDC.remove("traceId");
        MDC.remove("spanId");
    }

    private static final class RenewalFailedException extends RuntimeException {
        RenewalFailedException(String message) {
            super(message);
        }
    }
}
