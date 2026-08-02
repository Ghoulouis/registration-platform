package com.registration.client.retry;

import com.registration.client.net.TcpClient;
import com.registration.client.stats.OperationStats;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps {@link TcpClient#send} with exponential backoff + jitter (grilled Q7b), recording
 * stats per attempt, and applies the retry-own-terminal-status-is-success rule (ADR-0005).
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
     * @throws CallFailedException if every attempt (initial + retries) failed
     * @throws InterruptedException if interrupted while backing off between retries
     */
    public ProtocolMessage send(OperationType operationType, ProtocolMessage request) throws InterruptedException {
        OperationStats operationStats = stats.forType(operationType);
        int attempt = 0;

        while (true) {
            boolean isRetry = attempt > 0;
            attempt++;
            operationStats.recordAttempt(isRetry);

            long startNanos = System.nanoTime();
            try {
                ProtocolMessage response = tcpClient.send(request);
                operationStats.recordResponseTime((System.nanoTime() - startNanos) / 1_000_000);
                operationStats.recordOutcome(isSuccess(operationType, response, isRetry));
                return response;
            } catch (SocketTimeoutException e) {
                operationStats.recordTimeout();
                if (attempt > maxRetries) {
                    operationStats.recordOutcome(false);
                    throw new CallFailedException("Timed out after " + attempt + " attempts", e);
                }
            } catch (IOException e) {
                if (attempt > maxRetries) {
                    operationStats.recordOutcome(false);
                    throw new CallFailedException("Connection error after " + attempt + " attempts", e);
                }
            }

            Thread.sleep(backoffWithJitterMillis(attempt));
        }
    }

    private static boolean isSuccess(OperationType type, ProtocolMessage response, boolean isRetry) {
        StatusCode status = statusOf(response);
        if (status == StatusCode.SUCCESS) {
            return true;
        }
        if (!isRetry) {
            return false;
        }
        // ADR-0005: on a retry, our own earlier attempt may have already landed.
        return switch (type) {
            case REGISTER -> status == StatusCode.ALREADY_REGISTERED;
            case CANCEL -> status == StatusCode.NOT_REGISTERED;
            case RENEW -> false;
        };
    }

    private static StatusCode statusOf(ProtocolMessage response) {
        return switch (response) {
            case RegisterResponse r -> r.status();
            case RenewResponse r -> r.status();
            case CancelResponse r -> r.status();
            case RegisterRequest ignored -> throw new IllegalStateException("Not a response message: " + response);
            case RenewRequest ignored -> throw new IllegalStateException("Not a response message: " + response);
            case CancelRequest ignored -> throw new IllegalStateException("Not a response message: " + response);
        };
    }

    private long backoffWithJitterMillis(int attempt) {
        long exponential = baseDelay.toMillis() << Math.min(attempt - 1, 20);
        return exponential <= 0 ? 0 : ThreadLocalRandom.current().nextLong(exponential + 1);
    }
}
