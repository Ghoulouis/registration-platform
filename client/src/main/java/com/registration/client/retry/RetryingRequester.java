package com.registration.client.retry;

import com.registration.client.net.TcpClient;
import com.registration.client.stats.OperationStats;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import com.registration.common.crypto.Ed25519;
import com.registration.common.observability.RegistrationEventLog;
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
import org.slf4j.MDC;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

import static com.registration.common.observability.RegistrationEventLog.Level.TRACE;

/**
 * Wraps every REGISTER/RENEW/CANCEL attempt with exponential backoff + jitter (grilled
 * Q7b), recording stats per attempt, and applying the retry-own-terminal-status-is-success
 * rule (ADR-0005). The Trace itself belongs to the caller — one business transaction, one
 * Trace, decided by {@code SimulatedClient} (ADR-0012 revision: Trace ID generation moved
 * out of this class, since a generic retry/transport utility shouldn't be the one deciding
 * when a business transaction begins). This class only ever mints a fresh Span per
 * connection attempt (each retry, and Register's second leg) within the Trace it's given.
 */
public final class RetryingRequester {

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
    public RegisterResponse register(ClientId clientId, PrivateKey signingKey, TraceContext trace)
            throws InterruptedException {
        return executeWithRetry(OperationType.REGISTER, trace,
                t -> attemptRegister(clientId, signingKey, t),
                RetryingRequester::isRegisterSuccess);
    }

    /**
     * @throws CallFailedException if every attempt (initial + retries) failed
     * @throws InterruptedException if interrupted while backing off between retries
     */
    public RenewResponse renew(ClientId clientId, NonceSignature nonceSignature, TraceContext trace)
            throws InterruptedException {
        return executeWithRetry(OperationType.RENEW, trace,
                t -> (RenewResponse) tcpClient.send(new RenewRequest(clientId, t, nonceSignature)),
                RetryingRequester::isRenewSuccess);
    }

    /**
     * @throws CallFailedException if every attempt (initial + retries) failed
     * @throws InterruptedException if interrupted while backing off between retries
     */
    public CancelResponse cancel(ClientId clientId, NonceSignature nonceSignature, TraceContext trace)
            throws InterruptedException {
        return executeWithRetry(OperationType.CANCEL, trace,
                t -> (CancelResponse) tcpClient.send(new CancelRequest(clientId, t, nonceSignature)),
                RetryingRequester::isCancelSuccess);
    }

    private RegisterResponse attemptRegister(ClientId clientId, PrivateKey signingKey, TraceContext trace)
            throws IOException {
        RegistrationEventLog.log(clientId, "REGISTER", "requesting_nonce", TRACE);
        RegisterResponse initial = (RegisterResponse) tcpClient.send(RegisterRequest.initial(clientId, trace));
        if (initial.status() != StatusCode.CHALLENGE) {
            return initial; // ALREADY_REGISTERED: nothing to sign, no second leg
        }
        NonceSignature signature = Ed25519.sign(signingKey, initial.nonce());
        // Step 2 is a separate connection attempt - its own Span ID, same Trace (ADR-0012).
        TraceContext confirmSpan = trace.newSpan();
        putSpanId(confirmSpan);
        RegistrationEventLog.log(clientId, "REGISTER", "submitting_auth_data", TRACE);
        return (RegisterResponse) tcpClient.send(RegisterRequest.withNonceSignature(clientId, confirmSpan, signature));
    }

    private <T> T executeWithRetry(
            OperationType type, TraceContext trace, Attempt<T> attempt, ResultIsSuccess<T> isSuccess)
            throws InterruptedException {
        OperationStats operationStats = stats.forType(type);
        int attemptNumber = 0;

        while (true) {
            boolean isRetry = attemptNumber > 0;
            attemptNumber++;
            if (isRetry) {
                trace = trace.newSpan();
            }
            operationStats.recordAttempt(isRetry);

            long startNanos = System.nanoTime();
            putSpanId(trace);
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
            }

            Thread.sleep(backoffWithJitterMillis(attemptNumber));
        }
    }

    /** Only touches spanId - traceId belongs to the caller (SimulatedClient) for the whole transaction. */
    private static void putSpanId(TraceContext trace) {
        MDC.put("spanId", HexFormat.of().formatHex(trace.spanId()));
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
