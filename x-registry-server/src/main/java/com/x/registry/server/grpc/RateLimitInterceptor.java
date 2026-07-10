package com.x.registry.server.grpc;

import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final int maxRequestsPerSecond;
    private final Map<String, ClientRateState> clientStates = new ConcurrentHashMap<>();

    public RateLimitInterceptor(
            @Value("${x-registry.grpc.rate-limit.requests-per-second:1000}") int maxRequestsPerSecond) {
        this.maxRequestsPerSecond = maxRequestsPerSecond;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String clientIp = extractClientIp(call);
        if (clientIp.isEmpty()) {
            return next.startCall(call, headers);
        }

        ClientRateState state = clientStates.computeIfAbsent(clientIp, k -> new ClientRateState());
        if (!state.tryAcquire(maxRequestsPerSecond)) {
            log.warn("Rate limit exceeded for client {}", clientIp);
            call.close(Status.RESOURCE_EXHAUSTED
                    .withDescription("Rate limit exceeded: max " + maxRequestsPerSecond + " requests/second"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }

        return next.startCall(call, headers);
    }

    private <ReqT, RespT> String extractClientIp(ServerCall<ReqT, RespT> call) {
        SocketAddress remoteAddr = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (remoteAddr instanceof InetSocketAddress inet) {
            return inet.getAddress().getHostAddress();
        }
        return "";
    }

    static class ClientRateState {
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger count = new AtomicInteger(0);

        boolean tryAcquire(int maxPerSecond) {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start >= 1000) {
                windowStart.set(now);
                count.set(1);
                return true;
            }
            return count.incrementAndGet() <= maxPerSecond;
        }
    }
}
