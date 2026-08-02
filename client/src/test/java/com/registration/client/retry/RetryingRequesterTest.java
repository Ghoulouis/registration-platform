package com.registration.client.retry;

import com.registration.client.net.TcpClient;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import com.registration.client.testsupport.ScriptedTcpServer;
import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.Challenge;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.RegisterResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.PrivateKey;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryingRequesterTest {

    private static final ClientId CLIENT_ID = ClientId.parse("123456789012");
    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final Duration BASE_DELAY = Duration.ofMillis(5);

    // Demo Shared Signing Key (ADR-0009), only the private half is needed here.
    private static final PrivateKey SIGNING_KEY =
            Ed25519.parsePrivateKey("pU55QBNBWdgYnCyCaZsfU3jImcyqZKGmSv3Nb+YEEbM=");

    private ScriptedTcpServer server;
    private Stats stats;
    private RetryingRequester requester;

    @BeforeEach
    void setUp() throws IOException {
        server = new ScriptedTcpServer();
        stats = new Stats();
        TcpClient tcpClient = new TcpClient("localhost", server.port(), TIMEOUT);
        requester = new RetryingRequester(tcpClient, 3, BASE_DELAY, stats);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void succeedsOnFirstAttempt() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challenge(Challenge.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.success(60)));

        var response = requester.register(CLIENT_ID, SIGNING_KEY);

        assertThat(response).isEqualTo(RegisterResponse.success(60));
        var snapshot = stats.forType(OperationType.REGISTER).snapshot();
        assertThat(snapshot.totalAttempts()).isEqualTo(1);
        assertThat(snapshot.retryAttempts()).isEqualTo(0);
        assertThat(snapshot.successes()).isEqualTo(1);
    }

    @Test
    void succeedsAfterTimeoutThenRetrySucceeds() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.dropAfter(TIMEOUT.toMillis() + 100));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challenge(Challenge.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.success(60)));

        var response = requester.register(CLIENT_ID, SIGNING_KEY);

        assertThat(response).isEqualTo(RegisterResponse.success(60));
        var snapshot = stats.forType(OperationType.REGISTER).snapshot();
        assertThat(snapshot.totalAttempts()).isEqualTo(2);
        assertThat(snapshot.retryAttempts()).isEqualTo(1);
        assertThat(snapshot.timeouts()).isEqualTo(1);
        assertThat(snapshot.successes()).isEqualTo(1);
    }

    @Test
    void alreadyRegisteredOnRetryIsTreatedAsSuccess() throws InterruptedException {
        // ALREADY_REGISTERED short-circuits at Step 1 (no Challenge issued), so the retried
        // attempt is a single connection, same as the dropped first attempt.
        server.enqueue(ScriptedTcpServer.Behavior.dropAfter(TIMEOUT.toMillis() + 100));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.alreadyRegistered()));

        var response = requester.register(CLIENT_ID, SIGNING_KEY);

        assertThat(response).isEqualTo(RegisterResponse.alreadyRegistered());
        assertThat(stats.forType(OperationType.REGISTER).snapshot().successes()).isEqualTo(1);
        assertThat(stats.forType(OperationType.REGISTER).snapshot().failures()).isEqualTo(0);
    }

    @Test
    void alreadyRegisteredOnFirstAttemptIsAFailure() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.alreadyRegistered()));

        var response = requester.register(CLIENT_ID, SIGNING_KEY);

        assertThat(response).isEqualTo(RegisterResponse.alreadyRegistered());
        assertThat(stats.forType(OperationType.REGISTER).snapshot().successes()).isEqualTo(0);
        assertThat(stats.forType(OperationType.REGISTER).snapshot().failures()).isEqualTo(1);
    }

    @Test
    void challengeRejectedOnRetryIsStillAFailure() throws InterruptedException {
        // Unlike ALREADY_REGISTERED, CHALLENGE_REJECTED has no ADR-0005 retry exemption:
        // it doesn't mean our own earlier attempt landed.
        server.enqueue(ScriptedTcpServer.Behavior.dropAfter(TIMEOUT.toMillis() + 100));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challenge(Challenge.random())));
        server.enqueue(ScriptedTcpServer.Behavior.respond(RegisterResponse.challengeRejected()));

        var response = requester.register(CLIENT_ID, SIGNING_KEY);

        assertThat(response).isEqualTo(RegisterResponse.challengeRejected());
        assertThat(stats.forType(OperationType.REGISTER).snapshot().failures()).isEqualTo(1);
    }

    @Test
    void exhaustsRetriesAndThrows() {
        for (int i = 0; i < 4; i++) {
            server.enqueue(ScriptedTcpServer.Behavior.dropAfter(TIMEOUT.toMillis() + 100));
        }

        assertThatThrownBy(() -> requester.register(CLIENT_ID, SIGNING_KEY)).isInstanceOf(CallFailedException.class);

        var snapshot = stats.forType(OperationType.REGISTER).snapshot();
        assertThat(snapshot.totalAttempts()).isEqualTo(4);
        assertThat(snapshot.retryAttempts()).isEqualTo(3);
        assertThat(snapshot.timeouts()).isEqualTo(4);
        assertThat(snapshot.failures()).isEqualTo(1);
    }
}
