package com.registration.client.simulation;

import com.registration.client.config.ClientProperties;
import com.registration.client.net.TcpClient;
import com.registration.client.retry.RetryingRequester;
import com.registration.client.stats.Stats;
import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.ClientId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    public ClientSimulatorRunner(ClientProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        boolean benchmarkMode = properties.mode() == ClientProperties.Mode.BENCHMARK;
        int clientCount = benchmarkMode ? properties.simulatedClients() : 1;

        Stats stats = new Stats();
        List<Thread> threads = new ArrayList<>(clientCount);

        Thread shutdownHook = new Thread(() -> shutdown(threads, stats, benchmarkMode), "shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        launchSimulatedClients(clientCount, stats, threads);

        if (benchmarkMode) {
            reportPeriodicallyForDuration(stats, Duration.ofSeconds(properties.benchmarkDurationSeconds()));
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            shutdown(threads, stats, true);
        } else {
            threads.get(0).join();
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
                    properties.renewalWindowMaxPercent());

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
            log.info("\n{}", stats.report());
        }
    }

    private void shutdown(List<Thread> threads, Stats stats, boolean benchmarkMode) {
        log.info("Shutdown requested, cancelling {} Simulated Client(s)...", threads.size());
        threads.forEach(Thread::interrupt);
        threads.forEach(thread -> joinQuietly(thread, SHUTDOWN_JOIN_TIMEOUT));
        if (benchmarkMode) {
            log.info("Final statistics:\n{}", stats.report());
        }
    }

    private static void joinQuietly(Thread thread, Duration timeout) {
        try {
            thread.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
