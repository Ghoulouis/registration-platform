package com.registration.client.simulation;

import com.registration.client.net.TcpClient;
import com.registration.client.retry.RetryingRequester;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import com.registration.client.testsupport.ScriptedTcpServer;
import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.PrivateKey;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedClientTest {

    private static final ClientId CLIENT_ID = ClientId.parse("123456789012");

    // Demo Shared Signing Key (ADR-0009), only the private half is needed here.
    private static final PrivateKey SIGNING_KEY =
            Ed25519.parsePrivateKey("pU55QBNBWdgYnCyCaZsfU3jImcyqZKGmSv3Nb+YEEbM=");

    private ScriptedTcpServer server;
    private Stats stats;

    @BeforeEach
    void setUp() throws IOException {
        server = new ScriptedTcpServer();
        stats = new Stats();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void registersRenewsOnceThenCancelsOnInterrupt() throws InterruptedException {
        // Validity period 0 makes the first renewal fire immediately (0 * any% = 0 delay);
        // the renewed period of 10s then pushes the *next* renewal ~9-9.9s out, well past
        // this test's interrupt, so exactly one deterministic RENEW happens.
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challenge(Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.success(0, Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RenewResponse.success(10, Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(CancelResponse.success()));

        SimulatedClient client = newClient(0, 90, 99);
        Thread thread = Thread.ofVirtual().start(client);

        Thread.sleep(300); // let REGISTER + the one immediate RENEW happen
        thread.interrupt();
        thread.join(Duration.ofSeconds(2).toMillis());

        assertThat(thread.isAlive()).isFalse();
        assertThat(stats.forType(OperationType.REGISTER).snapshot().successes()).isEqualTo(1);
        assertThat(stats.forType(OperationType.RENEW).snapshot().successes()).isEqualTo(1);
        assertThat(stats.forType(OperationType.CANCEL).snapshot().successes()).isEqualTo(1);
    }

    @Test
    void cancelsOnInterruptWhileWaitingForRenewalWindow() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challenge(Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.success(10, Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(CancelResponse.success()));

        SimulatedClient client = newClient(10, 90, 99);
        Thread thread = Thread.ofVirtual().start(client);

        Thread.sleep(200); // let REGISTER complete; client is now waiting out its renewal window
        thread.interrupt();
        thread.join(Duration.ofSeconds(2).toMillis());

        assertThat(thread.isAlive()).isFalse();
        assertThat(stats.forType(OperationType.RENEW).snapshot().totalAttempts()).isEqualTo(0);
        assertThat(stats.forType(OperationType.CANCEL).snapshot().successes()).isEqualTo(1);
    }

    @Test
    void doesNotCancelOnInterruptWhenCancelOnExitIsFalse() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challenge(Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.success(10, Nonce.random())));

        SimulatedClient client = newClient(10, 90, 99, false);
        Thread thread = Thread.ofVirtual().start(client);

        Thread.sleep(200); // let REGISTER complete; client is now waiting out its renewal window
        thread.interrupt();
        thread.join(Duration.ofSeconds(2).toMillis());

        assertThat(thread.isAlive()).isFalse();
        assertThat(stats.forType(OperationType.CANCEL).snapshot().totalAttempts()).isEqualTo(0);
    }

    @Test
    void stopsWithoutCancelWhenRenewIsRejected() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challenge(Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.success(0, Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RenewResponse.notRegistered()));

        SimulatedClient client = newClient(0, 90, 99);
        Thread thread = Thread.ofVirtual().start(client);
        thread.join(Duration.ofSeconds(2).toMillis());

        assertThat(thread.isAlive()).isFalse();
        assertThat(stats.forType(OperationType.CANCEL).snapshot().totalAttempts()).isEqualTo(0);
    }

    @Test
    void stopsWithoutCancelWhenChallengeIsRejected() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challenge(Nonce.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challengeRejected()));

        SimulatedClient client = newClient(0, 90, 99);
        Thread thread = Thread.ofVirtual().start(client);
        thread.join(Duration.ofSeconds(2).toMillis());

        assertThat(thread.isAlive()).isFalse();
        assertThat(stats.forType(OperationType.REGISTER).snapshot().failures()).isEqualTo(1);
        assertThat(stats.forType(OperationType.CANCEL).snapshot().totalAttempts()).isEqualTo(0);
    }

    private SimulatedClient newClient(int assumedValidityPeriodSeconds, int renewalWindowMinPercent,
            int renewalWindowMaxPercent) {
        return newClient(assumedValidityPeriodSeconds, renewalWindowMinPercent, renewalWindowMaxPercent, true);
    }

    private SimulatedClient newClient(int assumedValidityPeriodSeconds, int renewalWindowMinPercent,
            int renewalWindowMaxPercent, boolean cancelOnExit) {
        TcpClient tcpClient = new TcpClient("localhost", server.port(), Duration.ofMillis(500));
        RetryingRequester requester = new RetryingRequester(tcpClient, 2, Duration.ofMillis(20), stats);
        return new SimulatedClient(CLIENT_ID, requester, SIGNING_KEY, assumedValidityPeriodSeconds,
                renewalWindowMinPercent, renewalWindowMaxPercent, cancelOnExit);
    }
}
