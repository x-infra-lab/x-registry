package com.x.registry.server.boot;

import com.x.registry.server.cluster.ClusterConfig;
import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.config.ConfigWatcherManager;
import com.x.registry.server.naming.PushAggregator;
import com.x.registry.server.storage.ConfigStore;
import com.x.registry.server.storage.InstanceStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ClusterBootConfig {

    @Value("${x-registry.cluster.enabled:false}")
    private boolean clusterEnabled;

    @Value("${x-registry.cluster.bind-address:127.0.0.1}")
    private String bindAddress;

    @Value("${x-registry.cluster.gossip-port:7848}")
    private int gossipPort;

    @Value("${x-registry.cluster.distro-port:7849}")
    private int distroPort;

    @Value("${x-registry.cluster.raft-port:7850}")
    private int raftPort;

    @Value("${x-registry.grpc.port:9848}")
    private int grpcPort;

    @Value("${x-registry.cluster.seed-nodes:}")
    private List<String> seedNodes;

    @Value("${x-registry.cluster.raft-data-dir:./data/raft}")
    private String raftDataDir;

    @Value("${x-registry.grpc.tls.enabled:false}")
    private boolean tlsEnabled;

    @Value("${x-registry.grpc.tls.cert-path:}")
    private String certPath;

    @Value("${x-registry.grpc.tls.key-path:}")
    private String keyPath;

    @Value("${x-registry.grpc.tls.trust-cert-path:}")
    private String trustCertPath;

    private final InstanceStore instanceStore;
    private final ConfigStore configStore;
    private final MeterRegistry meterRegistry;
    private final ConfigWatcherManager configWatcherManager;
    private final PushAggregator pushAggregator;
    private ClusterManager clusterManager;

    public ClusterBootConfig(InstanceStore instanceStore, ConfigStore configStore,
                             MeterRegistry meterRegistry, ConfigWatcherManager configWatcherManager,
                             PushAggregator pushAggregator) {
        this.instanceStore = instanceStore;
        this.configStore = configStore;
        this.meterRegistry = meterRegistry;
        this.configWatcherManager = configWatcherManager;
        this.pushAggregator = pushAggregator;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public ClusterManager clusterManager() {
        ClusterConfig config = new ClusterConfig()
                .setClusterEnabled(clusterEnabled)
                .setBindAddress(bindAddress)
                .setGossipPort(gossipPort)
                .setDistroPort(distroPort)
                .setRaftPort(raftPort)
                .setGrpcPort(grpcPort)
                .setSeedNodes(seedNodes != null ? seedNodes : List.of())
                .setRaftDataDir(raftDataDir)
                .setTlsEnabled(tlsEnabled)
                .setCertPath(certPath)
                .setKeyPath(keyPath)
                .setTrustCertPath(trustCertPath);

        clusterManager = new ClusterManager(config, instanceStore, configStore, meterRegistry);
        clusterManager.setConfigWatcherManager(configWatcherManager);
        clusterManager.setPushAggregator(pushAggregator);
        return clusterManager;
    }

}
