package com.registration.client.retry;

import com.registration.client.net.TcpClient;
import com.registration.client.stats.OperationStats;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.NonceSignature;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.common.protocol.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps every REGISTER/RENEW/CANCEL attempt with exponential backoff + jitter (grilled
 * Q7b), recording stats per attempt, applying the retry-own-terminal-status-is-success rule
 * (ADR-0005), and stamping every connection attempt with a Trace Context (ADR-0012): one
 * Trace ID per call to {@link #register}/{@link #renew}/{@link #cancel}, a fresh Span ID for
 * every retry (and, for Register, for each of its two legs).
 */
public final class RetryingRequester {

    private static final Logger log = LoggerFactory.getLogger(RetryingRequester.class);

    private final TcpClient tcpClient;
    private final int maxRetries;
    private final Duration baseDelay;
    private final Stats stats;

    public RetryingRequester(TcpClient tcpClient, int maxRetries, Duration baseDelay, Stats stats) {
        this.tcpClient = tcpClient;
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.stats = stats;
    }

    /**
     * REGISTER's two-step exchange (ADR-0009, ADR-0011), retried as one unit: any failure
     * restarts from Step 1 with a brand-new pending Nonce, never resuming one from a failed
     * prior attempt — the Client can't tell "my Nonce expired" from "my Nonce was already
     * consumed by a response that got lost on the way back."
     *
     * @throws CallFailedException if every attempt (initial + retries) failed
     * @throws InterruptedException if interrupted while backing off between retries
     */
    public RegisterResponse register(ClientId clientId, PrivateKey signingKey) throws InterruptedException {
        return executeWithRetry(OperationType.REGISTER,
                trace -> attemptRegister(clientId, signingKey, trace),
                RetryingRequester::isRegisterSuccess);
    }

    /**
     * @throws CallFailedException if every attempt (initial + retries) failed
     * @throws InterruptedException if interrupted while backing off between retries
     */
    public RenewResponse renew(ClientId clientId, NonceSignature nonceSignature) throws InterruptedException {
        return executeWithRetry(OperationType.RENEW,
                trace -> (RenewResponse) tcpClient.send(new RenewRequest(clientId, trace, nonceSignature)),
                RetryingRequester::isRenewSuccess);
    }

    /**
     * @throws CallFailedException if every attempt (initial + retries) failed
     * @throws InterruptedException if interrupted while backing off between retries
     */
    public CancelResponse cancel(ClientId clientId, NonceSignature nonceSignature) throws InterruptedException {
        return executeWithRetry(OperationType.CANCEL,
                trace -> (CancelResponse) tcpClient.send(new CancelRequest(clientId, trace, nonceSignature)),
                RetryingRequester::isCancelSuccess);
    }

    private RegisterResponse attemptRegister(ClientId clientId, PrivateKey signingKey, TraceContext trace)
            throws IOException {
        log.debug("[{}] REGISTER requesting_nonce", clientId);
        RegisterResponse initial = (RegisterResponse) tcpClient.send(RegisterRequest.initial(clientId, trace));
        if (initial.status() != StatusCode.CHALLENGE) {
            return initial; // ALREADY_REGISTERED: nothing to sign, no second leg
        }
        NonceSignature signature = Ed25519.sign(signingKey, initial.nonce());
        // Step 2 is a separate connection attempt - its own Span ID, same Trace (ADR-0012).
        TraceContext confirmSpan = trace.newSpan();
        putTraceContext(confirmSpan);
        log.debug("[{}] REGISTER submitting_auth_data", clientId);
        return (RegisterResponse) tcpClient.send(RegisterRequest.withNonceSignature(clientId, confirmSpan, signature));
    }

    private <T> T executeWithRetry(OperationType type, Attempt<T> attempt, ResultIsSuccess<T> isSuccess)
            throws InterruptedException {
        OperationStats operationStats = stats.forType(type);
        TraceContext trace = TraceContext.newTrace();
        int attemptNumber = 0;

        while (true) {
            boolean isRetry = attemptNumber > 0;
            attemptNumber++;
            if (isRetry) {
                trace = trace.newSpan();
            }
            operationStats.recordAttempt(isRetry);

            long startNanos = System.nanoTime();
            putTraceContext(trace);
            try {
                T response = attempt.run(trace);
                operationStats.recordResponseTime((System.nanoTime() - startNanos) / 1_000_000);
                operationStats.recordOutcome(isSuccess.test(response, isRetry));
                return response;
            } catch (SocketTimeoutException e) {
                operationStats.recordTimeout();
                if (attemptNumber > maxRetries) {
                    operationStats.recordOutcome(false);
                    throw new CallFailedException("Timed out after " + attemptNumber + " attempts", e);
                }
            } catch (IOException e) {
                if (attemptNumber > maxRetries) {
                    operationStats.recordOutcome(false);
                    throw new CallFailedException("Connection error after " + attemptNumber + " attempts", e);
                }
            } finally {
                clearTraceContext();
            }

            Thread.sleep(backoffWithJitterMillis(attemptNumber));
        }
    }

    /** Mirrors the Server's TcpServer MDC wiring (ADR-0012), so Client-side step logs (this class
     * only — SimulatedClient's own logs happen after this call returns, MDC already cleared)
     * carry the same traceId/spanId the Server logs for the matching connection attempt. */
    private static void putTraceContext(TraceContext trace) {
        HexFormat hex = HexFormat.of();
        MDC.put("traceId", hex.formatHex(trace.traceId()));
        MDC.put("spanId", hex.formatHex(trace.spanId()));
    }

    private static void clearTraceContext() {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }

    private static boolean isRegisterSuccess(RegisterResponse response, boolean isRetry) {
        if (response.status() == StatusCode.SUCCESS) {
            return true;
        }
        if (!isRetry) {
            return false;
        }
        // ADR-0005, extended to REGISTER's second leg: on a retry, our own earlier attempt
        // may have already landed and confirmed the Registration before we heard back.
        return response.status() == StatusCode.ALREADY_REGISTERED;
    }

    private static boolean isRenewSuccess(RenewResponse response, boolean isRetry) {
        // No ADR-0005 exemption needed: the Nonce grace window (ADR-0010) already makes a
        // retried Renewal come back as plain SUCCESS, transparently, with no special-casing.
        return response.status() == StatusCode.SUCCESS;
    }

    private static boolean isCancelSuccess(CancelResponse response, boolean isRetry) {
        if (response.status() == StatusCode.SUCCESS) {
            return true;
        }
        if (!isRetry) {
            return false;
        }
        // ADR-0005: on a retry, our own earlier attempt may have already landed.
        return response.status() == StatusCode.NOT_REGISTERED;
    }

    private long backoffWithJitterMillis(int attempt) {
        long exponential = baseDelay.toMillis() << Math.min(attempt - 1, 20);
        return exponential <= 0 ? 0 : ThreadLocalRandom.current().nextLong(exponential + 1);
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run(TraceContext trace) throws IOException;
    }

    @FunctionalInterface
    private interface ResultIsSuccess<T> {
        boolean test(T response, boolean isRetry);
    }
}
