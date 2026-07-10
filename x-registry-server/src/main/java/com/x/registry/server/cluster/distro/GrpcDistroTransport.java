package com.x.registry.server.cluster.distro;

import com.fasterxml.jackson.core.type.TypeReference;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class GrpcDistroTransport implements DistroTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcDistroTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MethodDescriptor<byte[], byte[]> SYNC_METHOD =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("xregistry.Distro/Sync")
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

    private static final MethodDescriptor<byte[], byte[]> FULL_SYNC_METHOD =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("xregistry.Distro/FullSync")
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

    private static final MethodDescriptor<byte[], byte[]> CHECKSUM_METHOD =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("xregistry.Distro/Checksum")
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

    private Server server;
    private Supplier<DistroProtocol> protocolSupplier;
    private SslContext serverSslContext;
    private SslContext clientSslContext;

    public void setTlsContext(SslContext serverSslContext, SslContext clientSslContext) {
        this.serverSslContext = serverSslContext;
        this.clientSslContext = clientSslContext;
    }

    public void startServer(int port, Supplier<DistroProtocol> protocolSupplier) {
        this.protocolSupplier = protocolSupplier;
        try {
            ServerServiceDefinition service = ServerServiceDefinition.builder("xregistry.Distro")
                    .addMethod(SYNC_METHOD, ServerCalls.asyncUnaryCall(this::handleSync))
                    .addMethod(FULL_SYNC_METHOD, ServerCalls.asyncUnaryCall(this::handleFullSync))
                    .addMethod(CHECKSUM_METHOD, ServerCalls.asyncUnaryCall(this::handleChecksum))
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
            log.info("Distro transport server started on port {}, tls={}", port, serverSslContext != null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start distro transport", e);
        }
    }

    public void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Override
    public boolean syncData(String address, int port, DistroData data) {
        ManagedChannel channel = null;
        try {
            if (clientSslContext != null) {
                channel = NettyChannelBuilder.forAddress(address, port)
                        .sslContext(clientSslContext)
                        .build();
            } else {
                channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
            }
            byte[] request = MAPPER.writeValueAsBytes(data);
            byte[] response = ClientCalls.blockingUnaryCall(
                    channel, SYNC_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(3000, TimeUnit.MILLISECONDS),
                    request);
            return response != null && response.length > 0 && response[0] == 1;
        } catch (Exception e) {
            log.debug("Sync to {}:{} failed: {}", address, port, e.getMessage());
            return false;
        } finally {
            if (channel != null) channel.shutdownNow();
        }
    }

    @Override
    public List<DistroData> requestFullSync(String address, int port) {
        ManagedChannel channel = null;
        try {
            if (clientSslContext != null) {
                channel = NettyChannelBuilder.forAddress(address, port)
                        .sslContext(clientSslContext)
                        .build();
            } else {
                channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
            }
            byte[] response = ClientCalls.blockingUnaryCall(
                    channel, FULL_SYNC_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(10000, TimeUnit.MILLISECONDS),
                    new byte[0]);
            return MAPPER.readValue(response, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Full sync from {}:{} failed: {}", address, port, e.getMessage());
            return null;
        } finally {
            if (channel != null) channel.shutdownNow();
        }
    }

    @Override
    public String getChecksum(String address, int port) {
        ManagedChannel channel = null;
        try {
            if (clientSslContext != null) {
                channel = NettyChannelBuilder.forAddress(address, port)
                        .sslContext(clientSslContext)
                        .build();
            } else {
                channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext().build();
            }
            byte[] response = ClientCalls.blockingUnaryCall(
                    channel, CHECKSUM_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(2000, TimeUnit.MILLISECONDS),
                    new byte[0]);
            return new String(response);
        } catch (Exception e) {
            return null;
        } finally {
            if (channel != null) channel.shutdownNow();
        }
    }

    private void handleSync(byte[] request, StreamObserver<byte[]> responseObserver) {
        try {
            DistroData data = MAPPER.readValue(request, DistroData.class);
            protocolSupplier.get().onReceiveSync(data);
            responseObserver.onNext(new byte[]{1});
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(new byte[]{0});
            responseObserver.onCompleted();
        }
    }

    private void handleFullSync(byte[] request, StreamObserver<byte[]> responseObserver) {
        try {
            List<DistroData> allData = protocolSupplier.get().handleFullSyncRequest();
            responseObserver.onNext(MAPPER.writeValueAsBytes(allData));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    private void handleChecksum(byte[] request, StreamObserver<byte[]> responseObserver) {
        // Return a simple checksum placeholder - real implementation would compute it
        responseObserver.onNext("0".getBytes());
        responseObserver.onCompleted();
    }

    private static class ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        static final ByteMarshaller INSTANCE = new ByteMarshaller();

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
