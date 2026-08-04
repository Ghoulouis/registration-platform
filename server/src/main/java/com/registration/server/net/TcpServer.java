package com.registration.server.net;

import com.registration.common.observability.RegistrationEventLog;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.FrameDecoder;
import com.registration.common.protocol.MessageCodec;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.TraceContext;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.domain.RegistrationService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

import static com.registration.common.observability.RegistrationEventLog.Level.INFO;

/**
 * One virtual thread per connection, doing plain blocking I/O (ADR-0015, Centralized Server
 * only — reverses ADR-0001) — same style {@link com.registration.client.net.TcpClient} (not
 * visible from here, different module) already uses. REGISTER/RENEW/CANCEL for different
 * Client IDs now genuinely run in parallel across cores, instead of being serialized through
 * one reactor thread. A {@link StripedLock} keyed by Client ID still serializes the
 * check-then-act sequences inside {@link RegistrationService} for the *same* Client ID, since
 * that safety no longer comes for free from single-threading.
 */
@Component
public class TcpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);
    private static final int READ_BUFFER_SIZE = 64;

    private final RegistrationProperties properties;
    private final RegistrationService registrationService;
    private final StripedLock locks = new StripedLock();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public TcpServer(RegistrationProperties properties, RegistrationService registrationService) {
        this.properties = properties;
        this.registrationService = registrationService;
    }

    @Override
    public void start() {
        try {
            serverSocket = new ServerSocket(properties.tcpPort());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start TCP server on port " + properties.tcpPort(), e);
        }

        running = true;
        acceptThread = new Thread(this::acceptLoop, "tcp-accept");
        acceptThread.start();
        log.info("Registration TCP server (standalone) listening on port {}", properties.tcpPort());
    }

    @Override
    public void stop() {
        running = false;
        closeQuietly(serverSocket); // unblocks the accept() call in acceptLoop
        if (acceptThread != null) {
            try {
                acceptThread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    RegistrationEventLog.log( "NETWORK", "Accept failed", INFO);
                }
                continue;
            }
            Thread.ofVirtual().start(() -> handleConnection(socket));
        }
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            ProtocolMessage request = readRequest(socket);
            ProtocolMessage response = handleWithLockAndTraceContext(request);
            writeResponse(socket, response);
        } catch (IOException e) {
            RegistrationEventLog.log( "NETWORK", "Connection error", INFO);
        } catch (RuntimeException e) {
            // FrameDecoder/MessageCodec reject malformed input (unknown MessageType, an
            // out-of-range or negative payload length, a payload that's the wrong shape for
            // its declared type) by throwing unchecked exceptions - isolate that to this one
            // connection's own virtual thread rather than anything shared (ADR-0015).
            RegistrationEventLog.log( "NETWORK", "Malformed frame", INFO);
        }
    }

    private static ProtocolMessage readRequest(Socket socket) throws IOException {
        FrameDecoder decoder = new FrameDecoder();
        byte[] readBuffer = new byte[READ_BUFFER_SIZE];
        while (!decoder.isComplete()) {
            int bytesRead = socket.getInputStream().read(readBuffer);
            if (bytesRead == -1) {
                throw new EOFException("Connection closed before a full frame was received");
            }
            decoder.feed(ByteBuffer.wrap(readBuffer, 0, bytesRead));
        }
        return decoder.decode();
    }

    private static void writeResponse(Socket socket, ProtocolMessage response) throws IOException {
        ByteBuffer frame = MessageCodec.encode(response);
        socket.getOutputStream().write(frame.array(), frame.arrayOffset() + frame.position(), frame.remaining());
        socket.getOutputStream().flush();
    }

    /**
     * Locks per Client ID (ADR-0015) around the business logic only — never around socket
     * I/O, which would stall every other request for the same Client ID for no reason while
     * blocked on the network — and puts the request's Trace Context (ADR-0012) into MDC for
     * the same scope, so every Registration Event Log line {@link RegistrationService} emits
     * (ADR-0014) carries the matching traceId/spanId.
     */
    private ProtocolMessage handleWithLockAndTraceContext(ProtocolMessage request) {
        ReentrantLock lock = locks.forClientId(clientIdOf(request));
        lock.lock();
        try {
            return handleWithTraceContext(request);
        } finally {
            lock.unlock();
        }
    }

    private ProtocolMessage handleWithTraceContext(ProtocolMessage request) {
        TraceContext trace = traceContextOf(request);
        if (trace == null) {
            return registrationService.handle(request);
        }
        HexFormat hex = HexFormat.of();
        String traceId = hex.formatHex(trace.traceId());
        String spanId = hex.formatHex(trace.spanId());
        MDC.put("traceId", traceId);
        MDC.put("spanId", spanId);

        // Wraps ADR-0012's own Trace ID/Span ID as a real (non-recording) OTel Span so the
        // Log Data Model's trace_id/span_id fields populate via the SDK's normal mechanism
        // (ADR-0018) - no span is ever recorded or exported, this is log correlation only.
        SpanContext spanContext = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope scope = Span.wrap(spanContext).storeInContext(Context.current()).makeCurrent()) {
            return registrationService.handle(request);
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }

    private static ClientId clientIdOf(ProtocolMessage request) {
        return switch (request) {
            case RegisterRequest r -> r.clientId();
            case RenewRequest r -> r.clientId();
            case CancelRequest r -> r.clientId();
            default -> throw new IllegalArgumentException("Server does not accept " + request.type());
        };
    }

    private static TraceContext traceContextOf(ProtocolMessage request) {
        return switch (request) {
            case RegisterRequest r -> r.traceContext();
            case RenewRequest r -> r.traceContext();
            case CancelRequest r -> r.traceContext();
            default -> null;
        };
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
