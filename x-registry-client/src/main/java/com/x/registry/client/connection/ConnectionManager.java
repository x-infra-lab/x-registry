package com.x.registry.client.connection;

import com.x.registry.client.XRegistryClientConfig;
import com.x.registry.client.event.ConnectionEvent;
import com.x.registry.client.event.EventBus;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages gRPC connections with failover support.
 * Supports multiple server addresses with automatic reconnection
 * using exponential backoff and random jitter.
 */
public class ConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private static final long BASE_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;
    private static final int MAX_RETRY_ATTEMPTS = 10;

    private static final long DEFAULT_CIRCUIT_BREAKER_COOLDOWN_MS = 30_000;
    private static final int DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD = 5;

    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }

    private final XRegistryClientConfig config;
    private final EventBus eventBus;
    private final List<ServerAddress> serverAddresses;
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private volatile ManagedChannel channel;
    private int consecutiveFailures = 0;

    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private volatile long circuitOpenedAt = 0;

    public ConnectionManager(XRegistryClientConfig config) {
        this(config, null);
    }

    public ConnectionManager(XRegistryClientConfig config, EventBus eventBus) {
        this.config = config;
        this.eventBus = eventBus;
        this.serverAddresses = parseServerAddresses(config.getServerAddr());
    }

    public CircuitState getCircuitState() {
        return circuitState;
    }

    public synchronized ManagedChannel getChannel() {
        // Circuit breaker check
        if (circuitState == CircuitState.OPEN) {
            long elapsed = System.currentTimeMillis() - circuitOpenedAt;
            if (elapsed < DEFAULT_CIRCUIT_BREAKER_COOLDOWN_MS) {
                throw new RuntimeException("Circuit breaker is OPEN; connections are temporarily blocked "
                        + "(cooldown remaining: " + (DEFAULT_CIRCUIT_BREAKER_COOLDOWN_MS - elapsed) + "ms)");
            }
            // Cooldown has elapsed, transition to HALF_OPEN and attempt reconnect
            log.info("Circuit breaker cooldown elapsed, transitioning to HALF_OPEN");
            circuitState = CircuitState.HALF_OPEN;
            return attemptReconnectForCircuitBreaker();
        }

        if (circuitState == CircuitState.HALF_OPEN) {
            return attemptReconnectForCircuitBreaker();
        }

        // CLOSED state: normal behavior
        if (channel != null && !channel.isShutdown()) {
            ConnectivityState state = channel.getState(false);
            if (state != ConnectivityState.SHUTDOWN && state != ConnectivityState.TRANSIENT_FAILURE) {
                return channel;
            }
            channel.shutdownNow();
        }

        return reconnect();
    }

    private ManagedChannel attemptReconnectForCircuitBreaker() {
        try {
            ManagedChannel result = reconnect();
            // Reconnect succeeded, close the circuit
            circuitState = CircuitState.CLOSED;
            log.info("Circuit breaker transitioned to CLOSED after successful reconnect");
            return result;
        } catch (Exception e) {
            // Reconnect failed, re-open the circuit
            circuitState = CircuitState.OPEN;
            circuitOpenedAt = System.currentTimeMillis();
            log.warn("Circuit breaker transitioned back to OPEN after failed reconnect in HALF_OPEN state");
            throw e;
        }
    }

    public synchronized ManagedChannel reconnect() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
            fireEvent(new ConnectionEvent(ConnectionEvent.Type.DISCONNECTED, null));
        }

        fireEvent(new ConnectionEvent(ConnectionEvent.Type.RECONNECTING, null));

        for (int attempt = 0; attempt < Math.min(serverAddresses.size(), MAX_RETRY_ATTEMPTS); attempt++) {
            ServerAddress addr = nextServer();
            try {
                channel = buildChannel(addr);

                // Verify connectivity
                ConnectivityState state = channel.getState(true);
                log.info("Connected to X-Registry server at {}:{}", addr.host, addr.port);
                consecutiveFailures = 0;
                fireEvent(new ConnectionEvent(ConnectionEvent.Type.CONNECTED, addr.toString()));
                return channel;
            } catch (Exception e) {
                log.warn("Failed to connect to {}:{}: {}", addr.host, addr.port, e.getMessage());
                consecutiveFailures++;
                if (consecutiveFailures >= DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD
                        && circuitState == CircuitState.CLOSED) {
                    circuitState = CircuitState.OPEN;
                    circuitOpenedAt = System.currentTimeMillis();
                    log.warn("Circuit breaker tripped to OPEN after {} consecutive failures", consecutiveFailures);
                }
            }
        }

        // All servers failed, use exponential backoff before next retry
        long backoff = calculateBackoff();
        log.error("All servers unreachable, will retry in {}ms", backoff);
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Last resort: try first server
        ServerAddress addr = serverAddresses.get(0);
        channel = buildChannel(addr);
        return channel;
    }

    @Override
    public void close() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
            fireEvent(new ConnectionEvent(ConnectionEvent.Type.DISCONNECTED, null));
            log.info("Disconnected from X-Registry server");
        }
    }

    private void fireEvent(ConnectionEvent event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    public List<ServerAddress> getServerAddresses() {
        return Collections.unmodifiableList(serverAddresses);
    }

    private ManagedChannel buildChannel(ServerAddress addr) {
        if (config.isTlsEnabled()) {
            try {
                SslContextBuilder sslBuilder = GrpcSslContexts.forClient();
                if (config.getTrustCertPath() != null && !config.getTrustCertPath().isEmpty()) {
                    sslBuilder.trustManager(new File(config.getTrustCertPath()));
                }
                if (config.getCertPath() != null && !config.getCertPath().isEmpty()
                        && config.getKeyPath() != null && !config.getKeyPath().isEmpty()) {
                    sslBuilder.keyManager(new File(config.getCertPath()), new File(config.getKeyPath()));
                }
                SslContext sslContext = sslBuilder.build();
                return NettyChannelBuilder.forAddress(addr.host, addr.port)
                        .sslContext(sslContext)
                        .keepAliveTime(10, TimeUnit.SECONDS)
                        .keepAliveTimeout(5, TimeUnit.SECONDS)
                        .build();
            } catch (SSLException e) {
                throw new RuntimeException("Failed to create TLS channel", e);
            }
        }
        return ManagedChannelBuilder
                .forAddress(addr.host, addr.port)
                .usePlaintext()
                .keepAliveTime(10, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    private ServerAddress nextServer() {
        int idx = currentIndex.getAndUpdate(i -> (i + 1) % serverAddresses.size());
        return serverAddresses.get(idx);
    }

    private long calculateBackoff() {
        long backoff = BASE_BACKOFF_MS * (1L << Math.min(consecutiveFailures, 5));
        backoff = Math.min(backoff, MAX_BACKOFF_MS);
        long jitter = ThreadLocalRandom.current().nextLong(backoff / 2);
        return backoff + jitter;
    }

    private List<ServerAddress> parseServerAddresses(String serverAddr) {
        List<ServerAddress> addresses = new ArrayList<>();
        String[] parts = serverAddr.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            String[] hostPort = trimmed.split(":");
            String host = hostPort[0];
            int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 9848;
            addresses.add(new ServerAddress(host, port));
        }
        return addresses;
    }

    public record ServerAddress(String host, int port) {
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
