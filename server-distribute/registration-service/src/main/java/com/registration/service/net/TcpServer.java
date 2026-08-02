package com.registration.service.net;

import com.registration.common.protocol.FrameDecoder;
import com.registration.common.protocol.MessageCodec;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.service.config.RegistrationProperties;
import com.registration.service.domain.RegistrationService;
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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-threaded NIO Selector event loop (ADR-0001): this thread only ever does
 * non-blocking accept/read/write. Once a full request frame is decoded, handling
 * (including the Redis round-trip) is dispatched to a worker pool so the reactor
 * thread is never blocked waiting on it. Worker threads and the reactive Redis
 * client's own callback threads never touch selector/channel state directly —
 * they enqueue a task onto {@link #pendingTasks} and call {@link Selector#wakeup()}
 * so only the reactor thread ever mutates a SelectionKey.
 */
@Component
public class TcpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);
    private static final int READ_BUFFER_SIZE = 64;

    private final RegistrationProperties properties;
    private final RegistrationService registrationService;
    private final Queue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    private ExecutorService workerPool;
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

        workerPool = Executors.newFixedThreadPool(properties.workerPoolSize());
        running = true;
        reactorThread = new Thread(this::runEventLoop, "tcp-reactor");
        reactorThread.start();
        log.info("Registration TCP server listening on port {}", properties.tcpPort());
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
        if (workerPool != null) {
            workerPool.shutdown();
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
            drainPendingTasks();
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

    private void drainPendingTasks() {
        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            task.run();
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
            key.interestOps(0);
            dispatch(key, connection);
        }
    }

    private void dispatch(SelectionKey key, Connection connection) {
        ProtocolMessage request = connection.frameDecoder.decode();
        workerPool.submit(() -> registrationService.handle(request)
                .subscribe(
                        response -> completeWith(key, MessageCodec.encode(response)),
                        error -> {
                            log.warn("Failed to handle request {}", request, error);
                            pendingTasks.add(() -> closeConnection(key));
                            selector.wakeup();
                        }));
    }

    private void completeWith(SelectionKey key, ByteBuffer responseFrame) {
        pendingTasks.add(() -> {
            if (!key.isValid()) {
                return;
            }
            ((Connection) key.attachment()).pendingWrite = responseFrame;
            key.interestOps(SelectionKey.OP_WRITE);
        });
        selector.wakeup();
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
