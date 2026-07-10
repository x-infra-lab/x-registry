package com.x.registry.server.cluster.gossip;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * gRPC-based gossip transport using a lightweight custom service.
 * Uses JSON serialization for GossipMessage to avoid adding more proto definitions in Phase 2.
 */
public class GrpcGossipTransport implements GossipTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcGossipTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MethodDescriptor<byte[], byte[]> PING_METHOD =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("xregistry.Gossip/Ping")
                    .setRequestMarshaller(ByteArrayMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteArrayMarshaller.INSTANCE)
                    .build();

    private static final MethodDescriptor<byte[], byte[]> INDIRECT_PING_METHOD =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("xregistry.Gossip/IndirectPing")
                    .setRequestMarshaller(ByteArrayMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteArrayMarshaller.INSTANCE)
                    .build();

    private Server server;
    private GossipMessageHandler handler;
    private SslContext serverSslContext;
    private SslContext clientSslContext;

    public void setTlsContext(SslContext serverSslContext, SslContext clientSslContext) {
        this.serverSslContext = serverSslContext;
        this.clientSslContext = clientSslContext;
    }

    @Override
    public void start(int port, GossipMessageHandler handler) {
        this.handler = handler;
        try {
            ServerServiceDefinition service = ServerServiceDefinition.builder("xregistry.Gossip")
                    .addMethod(PING_METHOD, ServerCalls.asyncUnaryCall(this::handlePingCall))
                    .addMethod(INDIRECT_PING_METHOD, ServerCalls.asyncUnaryCall(this::handleIndirectPingCall))
                    .build();

            if (serverSslContext != null) {
                server = NettyServerBuilder.forPort(port)
                        .sslContext(serverSslContext)
                        .addService(service)
                        .build()
                        .start();
            } else {
                server = ServerBuilder.forPort(port)
                        .addService(service)
                        .build()
                        .start();
            }
            log.info("Gossip transport started on port {}, tls={}", port, serverSslContext != null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start gossip transport", e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Override
    public GossipMessage sendPing(String address, int port, GossipMessage ping) {
        return sendPingWithTimeout(address, port, ping, 3000);
    }

    @Override
    public GossipMessage sendPingWithTimeout(String address, int port, GossipMessage ping, long timeoutMs) {
        ManagedChannel channel = null;
        try {
            if (clientSslContext != null) {
                channel = NettyChannelBuilder.forAddress(address, port)
                        .sslContext(clientSslContext)
                        .build();
            } else {
                channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
            }
            byte[] request = MAPPER.writeValueAsBytes(ping);
            byte[] response = ClientCalls.blockingUnaryCall(
                    channel, PING_METHOD, CallOptions.DEFAULT.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS),
                    request);
            return MAPPER.readValue(response, GossipMessage.class);
        } catch (Exception e) {
            log.debug("Ping to {}:{} failed: {}", address, port, e.getMessage());
            return null;
        } finally {
            if (channel != null) {
                channel.shutdownNow();
            }
        }
    }

    @Override
    public GossipMessage sendIndirectPing(String proberAddress, int proberPort,
                                          String targetAddress, int targetPort, GossipMessage ping) {
        ManagedChannel channel = null;
        try {
            if (clientSslContext != null) {
                channel = NettyChannelBuilder.forAddress(proberAddress, proberPort)
                        .sslContext(clientSslContext)
                        .build();
            } else {
                channel = ManagedChannelBuilder.forAddress(proberAddress, proberPort).usePlaintext().build();
            }
            IndirectPingRequest request = new IndirectPingRequest(targetAddress, targetPort, ping);
            byte[] reqBytes = MAPPER.writeValueAsBytes(request);
            byte[] response = ClientCalls.blockingUnaryCall(
                    channel, INDIRECT_PING_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(2000, TimeUnit.MILLISECONDS),
                    reqBytes);
            if (response == null || response.length == 0) return null;
            return MAPPER.readValue(response, GossipMessage.class);
        } catch (Exception e) {
            log.debug("IndirectPing via {}:{} failed: {}", proberAddress, proberPort, e.getMessage());
            return null;
        } finally {
            if (channel != null) {
                channel.shutdownNow();
            }
        }
    }

    private void handlePingCall(byte[] request, StreamObserver<byte[]> responseObserver) {
        try {
            GossipMessage ping = MAPPER.readValue(request, GossipMessage.class);
            GossipMessage ack = handler.onPing(ping);
            responseObserver.onNext(MAPPER.writeValueAsBytes(ack));
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to handle gossip ping", e);
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    private void handleIndirectPingCall(byte[] request, StreamObserver<byte[]> responseObserver) {
        try {
            IndirectPingRequest req = MAPPER.readValue(request, IndirectPingRequest.class);
            GossipMessage ack = handler.onIndirectPing(req.targetAddress, req.targetPort, req.ping);
            if (ack != null) {
                responseObserver.onNext(MAPPER.writeValueAsBytes(ack));
            } else {
                responseObserver.onNext(new byte[0]);
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    public static class IndirectPingRequest {
        public String targetAddress;
        public int targetPort;
        public GossipMessage ping;

        public IndirectPingRequest() {}

        public IndirectPingRequest(String targetAddress, int targetPort, GossipMessage ping) {
            this.targetAddress = targetAddress;
            this.targetPort = targetPort;
            this.ping = ping;
        }
    }

    private static class ByteArrayMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        static final ByteArrayMarshaller INSTANCE = new ByteArrayMarshaller();

        @Override
        public java.io.InputStream stream(byte[] value) {
            return new java.io.ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(java.io.InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
