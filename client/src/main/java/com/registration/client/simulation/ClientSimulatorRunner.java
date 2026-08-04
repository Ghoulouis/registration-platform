package com.registration.client.simulation;

import com.registration.client.config.ClientProperties;
import com.registration.client.net.TcpClient;
import com.registration.client.observability.OtelLogging;
import com.registration.client.retry.RetryingRequester;
import com.registration.client.stats.OperationStats;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import com.registration.common.crypto.Ed25519;
import com.registration.common.observability.RegistrationEventLog;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.RegisterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Launches Normal Mode (one Simulated Client, runs until killed) or Benchmark Mode
 * (N Simulated Clients, rate-limited REGISTER ramp-up, periodic stats reporting for
 * a configured Benchmark Duration) — see CONTEXT.md's "Client Simulator" terms.
 * Benchmark Mode self-terminates once its Benchmark Duration elapses; Normal Mode and
 * an early Ctrl+C both go through the same shutdown hook. Either way, every Simulated
 * Client gets the chance to send its voluntary CANCEL (ADR-0004) before the process exits.
 */
@Component
public class ClientSimulatorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClientSimulatorRunner.class);
    private static final Duration REPORT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration SHUTDOWN_JOIN_TIMEOUT = Duration.ofSeconds(5);

    private final ClientProperties properties;
    private final OtelLogging otelLogging;

    public ClientSimulatorRunner(ClientProperties properties, OtelLogging otelLogging) {
        this.properties = properties;
        this.otelLogging = otelLogging;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        boolean benchmarkMode = properties.mode() == ClientProperties.Mode.BENCHMARK;
        int clientCount = benchmarkMode ? properties.simulatedClients() : 1;

        Stats stats = new Stats();
        List<Thread> threads = new ArrayList<>(clientCount);

        Thread shutdownHook = new Thread(() -> shutdown(threads, stats, benchmarkMode), "shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        BenchmarkReport report = null;
        ScheduledExecutorService sampler = null;
        if (benchmarkMode) {
            // Started before launchSimulatedClients, not after: ramp-up (register-rate-limited,
            // so it can take a while at high simulated-clients counts) is part of the Active
            // phase's own process lifetime (CONTEXT.md), and belongs in the time series too -
            // otherwise the first snapshot shows ramp-up's totals as an instant jump rather
            // than the gradual climb it actually was.
            report = new BenchmarkReport();
            sampler = Executors.newSingleThreadScheduledExecutor(
                    r -> Thread.ofVirtual().name("benchmark-report-sampler").unstarted(r));
            BenchmarkReport finalReport = report;
            sampler.scheduleAtFixedRate(() -> finalReport.recordSnapshot(stats), 0, 1, TimeUnit.SECONDS);
        }

        launchSimulatedClients(clientCount, stats, threads);

        if (benchmarkMode) {
            reportPeriodicallyForDuration(stats, Duration.ofSeconds(properties.benchmarkDurationSeconds()));
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            shutdown(threads, stats, true);

            sampler.shutdown();
            // One final snapshot, only on natural self-termination (not an early Ctrl+C via
            // shutdownHook) - the CANCELs shutdown() just triggered have already landed in
            // stats by this point, so the time series' last point reflects the complete run.
            report.recordSnapshot(stats);
            report.write();
        } else {
            threads.getFirst().join();
        }
    }

    private void launchSimulatedClients(int clientCount, Stats stats, List<Thread> threads)
            throws InterruptedException {
        Duration timeout = Duration.ofMillis(properties.timeoutMillis());
        Duration retryBaseDelay = Duration.ofMillis(properties.retryBaseDelayMillis());
        long intervalNanos = (long) (1_000_000_000.0 / properties.registerRatePerSecond());
        PrivateKey signingKey = Ed25519.parsePrivateKey(properties.authPrivateKey());

        for (int i = 0; i < clientCount; i++) {
            TcpClient tcpClient = new TcpClient(properties.serverHost(), properties.serverPort(), timeout);
            RetryingRequester requester =
                    new RetryingRequester(tcpClient, properties.maxRetries(), retryBaseDelay, stats);
            SimulatedClient client = new SimulatedClient(
                    ClientId.random(),
                    requester,
                    signingKey,
                    properties.assumedValidityPeriodSeconds(),
                    properties.renewalWindowMinPercent(),
                    properties.renewalWindowMaxPercent(),
                    properties.cancelOnExit());

            threads.add(Thread.ofVirtual().name("simulated-client-" + i).start(client));

            if (i < clientCount - 1) {
                TimeUnit.NANOSECONDS.sleep(intervalNanos);
            }
        }
        log.info("Launched {} Simulated Client(s) in {} mode", clientCount, properties.mode());
    }

    private void reportPeriodicallyForDuration(Stats stats, Duration duration) throws InterruptedException {
        long remainingMillis = duration.toMillis();
        while (remainingMillis > 0) {
            long sleepMillis = Math.min(REPORT_INTERVAL.toMillis(), remainingMillis);
            Thread.sleep(sleepMillis);
            remainingMillis -= sleepMillis;
            logStats(stats);
        }
    }

    private void shutdown(List<Thread> threads, Stats stats, boolean benchmarkMode) {
        log.info("Shutdown requested, cancelling {} Simulated Client(s)...", threads.size());
        threads.forEach(Thread::interrupt);
        threads.forEach(thread -> joinQuietly(thread, SHUTDOWN_JOIN_TIMEOUT));
        if (benchmarkMode) {
            logStats(stats);
        }
        // Flush buffered OTLP logs (ADR-0018) only after every Simulated Client's final
        // CANCEL is logged - guaranteed ordering that @PreDestroy alone can't give here,
        // since it runs on Spring's own independent shutdown hook.
        otelLogging.shutdown();
    }

    private static void joinQuietly(Thread thread, Duration timeout) {
        try {
            thread.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Structured, per-metric replacement for {@code Stats}' old single-block text dump. */
    private static void logStats(Stats stats) {
        RegistrationEventLog.logData(
                "REGISTER", "actual_rate_per_second", RegistrationEventLog.Level.INFO,
                stats.actualRegistrationRatePerSecond());

        for (OperationType type : OperationType.values()) {
            OperationStats.Snapshot s = stats.forType(type).snapshot();
            String transaction = type.name();
            RegistrationEventLog.logData(transaction, "total_attempts", RegistrationEventLog.Level.INFO, s.totalAttempts());
            RegistrationEventLog.logData(transaction, "retry_attempts", RegistrationEventLog.Level.INFO, s.retryAttempts());
            RegistrationEventLog.logData(transaction, "timeouts", RegistrationEventLog.Level.INFO, s.timeouts());
            RegistrationEventLog.logData(transaction, "successes", RegistrationEventLog.Level.INFO, s.successes());
            RegistrationEventLog.logData(transaction, "failures", RegistrationEventLog.Level.INFO, s.failures());
            RegistrationEventLog.logData(transaction, "avg_response_time_ms", RegistrationEventLog.Level.INFO, s.averageResponseTimeMillis());
            RegistrationEventLog.logData(transaction, "min_response_time_ms", RegistrationEventLog.Level.INFO, s.minResponseTimeMillis());
            RegistrationEventLog.logData(transaction, "max_response_time_ms", RegistrationEventLog.Level.INFO, s.maxResponseTimeMillis());
        }
    }
}
