package com.x.registry.server.boot;

import com.x.registry.server.grpc.ConfigGrpcServiceImpl;
import com.x.registry.server.grpc.ConnectionInterceptor;
import com.x.registry.server.grpc.ConnectionTracker;
import com.x.registry.server.grpc.NamingGrpcServiceImpl;
import com.x.registry.server.grpc.RateLimitInterceptor;
import com.x.registry.server.security.AuthInterceptor;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcServerConfig implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    @Value("${x-registry.grpc.port:9848}")
    private int grpcPort;

    @Value("${x-registry.grpc.tls.enabled:false}")
    private boolean tlsEnabled;

    @Value("${x-registry.grpc.tls.cert-path:}")
    private String certPath;

    @Value("${x-registry.grpc.tls.key-path:}")
    private String keyPath;

    @Value("${x-registry.grpc.tls.trust-cert-path:}")
    private String trustCertPath;

    private final NamingGrpcServiceImpl namingGrpcService;
    private final ConfigGrpcServiceImpl configGrpcService;
    private final ConnectionInterceptor connectionInterceptor;
    private final ConnectionTracker connectionTracker;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Autowired(required = false)
    private AuthInterceptor authInterceptor;

    private Server server;

    public GrpcServerConfig(NamingGrpcServiceImpl namingGrpcService, ConfigGrpcServiceImpl configGrpcService,
                            ConnectionInterceptor connectionInterceptor, ConnectionTracker connectionTracker,
                            RateLimitInterceptor rateLimitInterceptor) {
        this.namingGrpcService = namingGrpcService;
        this.configGrpcService = configGrpcService;
        this.connectionInterceptor = connectionInterceptor;
        this.connectionTracker = connectionTracker;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        NettyServerBuilder builder = NettyServerBuilder.forPort(grpcPort)
                .permitKeepAliveTime(5, java.util.concurrent.TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .addService(namingGrpcService)
                .addService(configGrpcService)
                .addTransportFilter(connectionTracker)
                .intercept(connectionInterceptor)
                .intercept(rateLimitInterceptor);

        if (authInterceptor != null) {
            builder.intercept(authInterceptor);
            log.info("gRPC auth interceptor enabled");
        }

        if (tlsEnabled && certPath != null && !certPath.isEmpty()) {
            java.io.File certFile = new java.io.File(certPath);
            java.io.File keyFile = new java.io.File(keyPath);
            io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder sslBuilder =
                    io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forServer(certFile, keyFile);
            if (trustCertPath != null && !trustCertPath.isEmpty()) {
                sslBuilder.trustManager(new java.io.File(trustCertPath))
                        .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.OPTIONAL);
            }
            builder.sslContext(sslBuilder.build());
            log.info("gRPC TLS enabled with cert={}", certPath);
        }

        server = builder.build().start();
        log.info("gRPC server started on port {}, tls={}", grpcPort, tlsEnabled);
    }

    @Override
    public void destroy() {
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    server.shutdownNow();
                    log.warn("gRPC server forced shutdown after 30s drain timeout");
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("gRPC server stopped");
        }
    }
}
