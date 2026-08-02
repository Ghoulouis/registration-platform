package com.registration.server.net;

import com.registration.common.protocol.FrameDecoder;
import com.registration.common.protocol.MessageCodec;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.domain.RegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Iterator;

/**
 * Single-threaded NIO Selector event loop (ADR-0001, ADR-0007) — same reactor shape as
 * the Distributed Server, but with no worker pool: {@link RegistrationService} here is
 * backed by an in-memory store with no I/O to await, so REGISTER/RENEW/CANCEL are
 * handled inline, directly on the reactor thread, right after a frame decodes.
 */
@Component
public class TcpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);
    private static final int READ_BUFFER_SIZE = 64;

    private final RegistrationProperties properties;
    private final RegistrationService registrationService;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread reactorThread;
    private volatile boolean running;

    public TcpServer(RegistrationProperties properties, RegistrationService registrationService) {
        this.properties = properties;
        this.registrationService = registrationService;
    }

    @Override
    public void start() {
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(properties.tcpPort()));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start TCP server on port " + properties.tcpPort(), e);
        }

        running = true;
        reactorThread = new Thread(this::runEventLoop, "tcp-reactor");
        reactorThread.start();
        log.info("Registration TCP server (standalone) listening on port {}", properties.tcpPort());
    }

    @Override
    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        if (reactorThread != null) {
            try {
                reactorThread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly(serverChannel);
        closeQuietly(selector);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runEventLoop() {
        while (running) {
            try {
                selector.select();
            } catch (IOException e) {
                log.error("Selector failed", e);
                continue;
            }

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) {
                    continue;
                }
                try {
                    if (key.isAcceptable()) {
                        acceptConnection();
                    } else if (key.isReadable()) {
                        readFrom(key);
                    } else if (key.isWritable()) {
                        writeTo(key);
                    }
                } catch (IOException e) {
                    log.debug("Connection error, closing", e);
                    closeConnection(key);
                }
            }
        }
    }

    private void acceptConnection() throws IOException {
        SocketChannel channel = serverChannel.accept();
        if (channel == null) {
            return;
        }
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ, new Connection());
    }

    private void readFrom(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection connection = (Connection) key.attachment();

        ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        int bytesRead = channel.read(readBuffer);
        if (bytesRead == -1) {
            closeConnection(key);
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        readBuffer.flip();
        connection.frameDecoder.feed(readBuffer);

        if (connection.frameDecoder.isComplete()) {
            ProtocolMessage request = connection.frameDecoder.decode();
            ProtocolMessage response = registrationService.handle(request);
            connection.pendingWrite = MessageCodec.encode(response);
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void writeTo(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection connection = (Connection) key.attachment();
        channel.write(connection.pendingWrite);
        if (!connection.pendingWrite.hasRemaining()) {
            closeConnection(key);
        }
    }

    private void closeConnection(SelectionKey key) {
        key.cancel();
        closeQuietly(key.channel());
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

    private static final class Connection {
        final FrameDecoder frameDecoder = new FrameDecoder();
        ByteBuffer pendingWrite;
    }
}
