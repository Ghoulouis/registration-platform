package com.registration.client.simulation;

import com.registration.client.retry.CallFailedException;
import com.registration.client.retry.RetryingRequester;
import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.NonceSignature;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.common.protocol.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SimulatedClient.class);

    private final ClientId clientId;
    private final RetryingRequester requester;
    private final PrivateKey signingKey;
    private final int assumedValidityPeriodSeconds;
    private final int renewalWindowMinPercent;
    private final int renewalWindowMaxPercent;

    // Set from Register's response, updated after every successful Renewal (ADR-0010).
    // Touched only by this Simulated Client's own thread - no synchronization needed.
    private Nonce currentNonce;

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
                log.debug("[{}] REGISTER success -> SUCCESS (validity period {}s)", clientId, response.validityPeriodSeconds());
                return response.validityPeriodSeconds();
            }
            if (response.status() == StatusCode.ALREADY_REGISTERED) {
                currentNonce = response.nonce();
                log.info("[{}] REGISTER already_registered -> REJECTED (proceeding as registered, "
                        + "assumed validity period {}s)", clientId, assumedValidityPeriodSeconds);
                return assumedValidityPeriodSeconds;
            }
            throw new CallFailedException("REGISTER challenge rejected");
        } catch (CallFailedException e) {
            log.debug("[{}] REGISTER failed -> REJECTED: {}", clientId, e.getMessage());
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
                response = requester.renew(clientId, signature, trace);
            } catch (CallFailedException e) {
                throw new RenewalFailedException(e.getMessage());
            }
            if (response.status() != StatusCode.SUCCESS) {
                throw new RenewalFailedException("Server returned " + response.status());
            }
            currentNonce = response.nonce();
            log.debug("[{}] RENEW success -> SUCCESS (validity period {}s)", clientId, response.validityPeriodSeconds());
            return response.validityPeriodSeconds();
        } catch (RenewalFailedException e) {
            log.debug("[{}] RENEW failed -> REJECTED: {}", clientId, e.getMessage());
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
            String result = response.status() == StatusCode.SUCCESS ? "SUCCESS" : "REJECTED";
            log.info("[{}] CANCEL {} -> {}", clientId, response.status().name().toLowerCase(), result);
        } catch (CallFailedException e) {
            log.debug("[{}] CANCEL failed -> REJECTED (best-effort during shutdown): {}", clientId, e.getMessage());
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

    private static void putTraceContext(TraceContext trace) {
        HexFormat hex = HexFormat.of();
        MDC.put("traceId", hex.formatHex(trace.traceId()));
        MDC.put("spanId", hex.formatHex(trace.spanId()));
    }

    private static void clearTraceContext() {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }

    private static final class RenewalFailedException extends RuntimeException {
        RenewalFailedException(String message) {
            super(message);
        }
    }
}
