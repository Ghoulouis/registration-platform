package com.registration.client.retry;

import com.registration.client.net.TcpClient;
import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import com.registration.client.testsupport.ScriptedTcpServer;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.StatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryingRequesterTest {

    private static final ClientId CLIENT_ID = ClientId.parse("123456789012");
    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final Duration BASE_DELAY = Duration.ofMillis(5);

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
        server.enqueue(ScriptedTcpServer.Behavior.respond(new RegisterResponse(StatusCode.SUCCESS, 60)));

        var response = requester.send(OperationType.REGISTER, new RegisterRequest(CLIENT_ID));

        assertThat(response).isEqualTo(new RegisterResponse(StatusCode.SUCCESS, 60));
        var snapshot = stats.forType(OperationType.REGISTER).snapshot();
        assertThat(snapshot.totalAttempts()).isEqualTo(1);
        assertThat(snapshot.retryAttempts()).isEqualTo(0);
        assertThat(snapshot.successes()).isEqualTo(1);
    }

    @Test
    void succeedsAfterTimeoutThenRetrySucceeds() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.dropAfter(TIMEOUT.toMillis() + 100));
        server.enqueue(ScriptedTcpServer.Behavior.respond(new RegisterResponse(StatusCode.SUCCESS, 60)));

        var response = requester.send(OperationType.REGISTER, new RegisterRequest(CLIENT_ID));

        assertThat(response).isEqualTo(new RegisterResponse(StatusCode.SUCCESS, 60));
        var snapshot = stats.forType(OperationType.REGISTER).snapshot();
        assertThat(snapshot.totalAttempts()).isEqualTo(2);
        assertThat(snapshot.retryAttempts()).isEqualTo(1);
        assertThat(snapshot.timeouts()).isEqualTo(1);
        assertThat(snapshot.successes()).isEqualTo(1);
    }

    @Test
    void alreadyRegisteredOnRetryIsTreatedAsSuccess() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.dropAfter(TIMEOUT.toMillis() + 100));
        server.enqueue(ScriptedTcpServer.Behavior.respond(new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0)));

        var response = requester.send(OperationType.REGISTER, new RegisterRequest(CLIENT_ID));

        assertThat(response).isEqualTo(new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0));
        assertThat(stats.forType(OperationType.REGISTER).snapshot().successes()).isEqualTo(1);
        assertThat(stats.forType(OperationType.REGISTER).snapshot().failures()).isEqualTo(0);
    }

    @Test
    void alreadyRegisteredOnFirstAttemptIsAFailure() throws InterruptedException {
        server.enqueue(ScriptedTcpServer.Behavior.respond(new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0)));

        var response = requester.send(OperationType.REGISTER, new RegisterRequest(CLIENT_ID));

        assertThat(response).isEqualTo(new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0));
        assertThat(stats.forType(OperationType.REGISTER).snapshot().successes()).isEqualTo(0);
        assertThat(stats.forType(OperationType.REGISTER).snapshot().failures()).isEqualTo(1);
    }

    @Test
    void exhaustsRetriesAndThrows() {
        for (int i = 0; i < 4; i++) {
            server.enqueue(ScriptedTcpServer.Behavior.dropAfter(TIMEOUT.toMillis() + 100));
        }

        assertThatThrownBy(() -> requester.send(OperationType.REGISTER, new RegisterRequest(CLIENT_ID)))
                .isInstanceOf(CallFailedException.class);

        var snapshot = stats.forType(OperationType.REGISTER).snapshot();
        assertThat(snapshot.totalAttempts()).isEqualTo(4);
        assertThat(snapshot.retryAttempts()).isEqualTo(3);
        assertThat(snapshot.timeouts()).isEqualTo(4);
        assertThat(snapshot.failures()).isEqualTo(1);
    }
}
