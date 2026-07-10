package com.x.registry.server.cluster;

import com.x.registry.server.cluster.distro.DistroConfig;
import com.x.registry.server.cluster.distro.DistroProtocol;
import com.x.registry.server.cluster.distro.GrpcDistroTransport;
import com.x.registry.server.cluster.gossip.GossipConfig;
import com.x.registry.server.cluster.gossip.GossipMessage;
import com.x.registry.server.cluster.gossip.GossipProtocol;
import com.x.registry.server.cluster.gossip.GossipTransport;
import com.x.registry.server.cluster.gossip.GrpcGossipTransport;
import com.x.registry.server.cluster.raft.ConfigStateMachine;
import com.x.registry.server.cluster.raft.LogEntry;
import com.x.registry.server.cluster.raft.RatisConfig;
import com.x.registry.server.cluster.raft.RatisServer;
import com.x.registry.server.storage.ConfigStore;
import com.x.registry.server.storage.InstanceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates all cluster protocols: Gossip for member discovery,
 * Distro for AP data sync, and Raft for CP data consistency.
 */
public class ClusterManager {

    private static final Logger log = LoggerFactory.getLogger(ClusterManager.class);

    private final ClusterConfig clusterConfig;
    private final MemberManager memberManager;
    private final InstanceStore instanceStore;
    private final ConfigStore configStore;
    private final MeterRegistry meterRegistry;
    private com.x.registry.server.config.ConfigWatcherManager configWatcherManager;
    private com.x.registry.server.naming.PushAggregator pushAggregator;

    private GossipProtocol gossipProtocol;
    private DistroProtocol distroProtocol;
    private RatisServer ratisServer;
    private ConfigStateMachine configStateMachine;
    private GrpcGossipTransport gossipTransport;
    private GrpcDistroTransport distroTransport;

    private volatile boolean clustered = false;

    public ClusterManager(ClusterConfig clusterConfig, InstanceStore instanceStore, ConfigStore configStore) {
        this(clusterConfig, instanceStore, configStore, null);
    }

    public ClusterManager(ClusterConfig clusterConfig, InstanceStore instanceStore,
                          ConfigStore configStore, MeterRegistry meterRegistry) {
        this.clusterConfig = clusterConfig;
        this.instanceStore = instanceStore;
        this.configStore = configStore;
        this.meterRegistry = meterRegistry;

        Member self = new Member(clusterConfig.getBindAddress(),
                clusterConfig.getGossipPort(), clusterConfig.getGrpcPort(),
                clusterConfig.getDistroPort(), clusterConfig.getRaftPort());
        this.memberManager = new MemberManager(self);
    }

    public void setConfigWatcherManager(com.x.registry.server.config.ConfigWatcherManager configWatcherManager) {
        this.configWatcherManager = configWatcherManager;
    }

    public void setPushAggregator(com.x.registry.server.naming.PushAggregator pushAggregator) {
        this.pushAggregator = pushAggregator;
    }

    public void start() {
        if (!clusterConfig.isClusterEnabled()) {
            log.info("Running in standalone mode, cluster protocols disabled");
            return;
        }

        clustered = true;

        // Start Gossip
        GossipConfig gossipConfig = new GossipConfig()
                .setGossipPort(clusterConfig.getGossipPort())
                .setProbeIntervalMs(clusterConfig.getGossipProbeIntervalMs())
                .setSuspectTimeoutMs(clusterConfig.getGossipSuspectTimeoutMs());

        gossipTransport = new GrpcGossipTransport();

        // Start Distro (transport created early so TLS can be configured)
        distroTransport = new GrpcDistroTransport();

        // Configure TLS for inter-node communication if enabled
        if (clusterConfig.isTlsEnabled()
                && clusterConfig.getCertPath() != null && !clusterConfig.getCertPath().isEmpty()) {
            try {
                File certFile = new File(clusterConfig.getCertPath());
                File keyFile = new File(clusterConfig.getKeyPath());

                SslContextBuilder serverSslBuilder = GrpcSslContexts.forServer(certFile, keyFile);
                if (clusterConfig.getTrustCertPath() != null && !clusterConfig.getTrustCertPath().isEmpty()) {
                    serverSslBuilder.trustManager(new File(clusterConfig.getTrustCertPath()))
                            .clientAuth(ClientAuth.OPTIONAL);
                }
                SslContext serverSslContext = serverSslBuilder.build();

                SslContextBuilder clientSslBuilder = GrpcSslContexts.forClient();
                if (clusterConfig.getTrustCertPath() != null && !clusterConfig.getTrustCertPath().isEmpty()) {
                    clientSslBuilder.trustManager(new File(clusterConfig.getTrustCertPath()));
                }
                clientSslBuilder.keyManager(certFile, keyFile);
                SslContext clientSslContext = clientSslBuilder.build();

                gossipTransport.setTlsContext(serverSslContext, clientSslContext);
                distroTransport.setTlsContext(serverSslContext, clientSslContext);
                log.info("TLS enabled for inter-node gossip and distro transports");
            } catch (Exception e) {
                throw new RuntimeException("Failed to configure TLS for cluster transports", e);
            }
        }

        gossipProtocol = new GossipProtocol(memberManager, gossipTransport, gossipConfig);

        gossipTransport.start(clusterConfig.getGossipPort(), new GossipTransport.GossipMessageHandler() {
            @Override
            public GossipMessage onPing(GossipMessage ping) {
                return gossipProtocol.handlePing(ping);
            }

            @Override
            public GossipMessage onIndirectPing(String targetAddress, int targetPort, GossipMessage ping) {
                return gossipProtocol.handleIndirectPing(targetAddress, targetPort, ping);
            }
        });
        gossipProtocol.start();

        // Configure and start Distro protocol
        DistroConfig distroConfig = new DistroConfig()
                .setVerifyIntervalMs(clusterConfig.getDistroVerifyIntervalMs());

        distroProtocol = new DistroProtocol(memberManager, instanceStore, distroTransport, distroConfig, meterRegistry);
        if (pushAggregator != null) {
            distroProtocol.setSyncListener((ns, group, svc) -> pushAggregator.markDirty(ns, group, svc));
        }
        distroTransport.startServer(clusterConfig.getDistroPort(), () -> distroProtocol);
        distroProtocol.start();

        // Setup member event listener for failover
        memberManager.addListener(this::onMemberEvent);

        // Join cluster via seed nodes
        if (!clusterConfig.getSeedNodes().isEmpty()) {
            gossipProtocol.join(clusterConfig.getSeedNodes());

            // Request full data sync from first alive peer
            List<Member> peers = memberManager.getAliveMembersExcludingSelf();
            if (!peers.isEmpty()) {
                distroProtocol.requestFullSync(peers.get(0));
            }
        }

        // Start Raft for CP consistency (config data + persistent instances)
        startRaft();

        log.info("Cluster manager started: gossipPort={}, distroPort={}, raftPort={}, seeds={}",
                clusterConfig.getGossipPort(), clusterConfig.getDistroPort(),
                clusterConfig.getRaftPort(), clusterConfig.getSeedNodes());
    }

    private void startRaft() {
        String selfRaftId = clusterConfig.getBindAddress() + ":" + clusterConfig.getRaftPort();

        List<String> raftPeers = new java.util.ArrayList<>();
        raftPeers.add(selfRaftId);
        for (Member member : memberManager.getAliveMembersExcludingSelf()) {
            String peerRaftId = member.getAddress() + ":" + member.getRaftPort();
            if (!raftPeers.contains(peerRaftId)) {
                raftPeers.add(peerRaftId);
            }
        }

        configStateMachine = new ConfigStateMachine(configStore, instanceStore);
        configStateMachine.setWatcherManager(configWatcherManager);

        RatisConfig ratisConfig = new RatisConfig()
                .setInitialMembers(raftPeers)
                .setDataDir(clusterConfig.getRaftDataDir());

        ratisServer = new RatisServer(selfRaftId, ratisConfig, configStateMachine);
        try {
            ratisServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Ratis server", e);
        }

        log.info("Raft (Ratis) started: nodeId={}, peers={}", selfRaftId, raftPeers);
    }

    public void stop() {
        if (ratisServer != null && ratisServer.isLeader()) {
            ratisServer.transferLeadership(null);
        }
        if (gossipProtocol != null) gossipProtocol.leave();
        if (distroProtocol != null) distroProtocol.stop();
        if (ratisServer != null) ratisServer.stop();
        if (gossipProtocol != null) gossipProtocol.stop();
        if (gossipTransport != null) gossipTransport.stop();
        if (distroTransport != null) distroTransport.stopServer();
    }

    public boolean isClustered() {
        return clustered;
    }

    public MemberManager getMemberManager() {
        return memberManager;
    }

    public DistroProtocol getDistroProtocol() {
        return distroProtocol;
    }

    public RatisServer getRatisServer() {
        return ratisServer;
    }

    public ConfigStateMachine getConfigStateMachine() {
        return configStateMachine;
    }

    public CompletableFuture<Boolean> proposeConfigPublish(String namespace, String dataId, String group,
                                                            String content, String contentType,
                                                            String operator, String description) {
        if (ratisServer == null || !ratisServer.isLeader()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] data = mapper.writeValueAsBytes(Map.of(
                    "namespace", namespace, "group", group, "dataId", dataId,
                    "content", content,
                    "contentType", contentType != null ? contentType : "text",
                    "operator", operator != null ? operator : "",
                    "description", description != null ? description : ""
            ));
            return ratisServer.propose(LogEntry.Type.CONFIG_PUBLISH, data);
        } catch (Exception e) {
            log.error("Failed to propose config publish", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<Boolean> proposeConfigDelete(String namespace, String dataId, String group) {
        if (ratisServer == null || !ratisServer.isLeader()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] data = mapper.writeValueAsBytes(Map.of(
                    "namespace", namespace, "group", group, "dataId", dataId
            ));
            return ratisServer.propose(LogEntry.Type.CONFIG_DELETE, data);
        } catch (Exception e) {
            log.error("Failed to propose config delete", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<Boolean> proposeInstanceRegister(String namespace, String group,
                                                               String serviceName,
                                                               com.x.registry.api.model.Instance instance) {
        if (ratisServer == null || !ratisServer.isLeader()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] data = mapper.writeValueAsBytes(Map.of(
                    "namespace", namespace, "group", group,
                    "serviceName", serviceName, "instance", instance
            ));
            return ratisServer.propose(LogEntry.Type.INSTANCE_REGISTER_PERSISTENT, data);
        } catch (Exception e) {
            log.error("Failed to propose instance register", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<Boolean> proposeInstanceDeregister(String namespace, String group,
                                                                 String serviceName,
                                                                 com.x.registry.api.model.Instance instance) {
        if (ratisServer == null || !ratisServer.isLeader()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] data = mapper.writeValueAsBytes(Map.of(
                    "namespace", namespace, "group", group,
                    "serviceName", serviceName, "instance", instance
            ));
            return ratisServer.propose(LogEntry.Type.INSTANCE_DEREGISTER_PERSISTENT, data);
        } catch (Exception e) {
            log.error("Failed to propose instance deregister", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public Collection<Member> getMembers() {
        return memberManager.getAllMembers();
    }

    public boolean isLeader() {
        return ratisServer != null && ratisServer.isLeader();
    }

    public String getLeaderId() {
        return ratisServer != null ? ratisServer.getLeaderId() : memberManager.getSelf().getId();
    }

    private void onMemberEvent(MemberManager.MemberEvent event) {
        switch (event.getType()) {
            case JOINED -> {
                log.info("Member joined cluster: {}", event.getMember().getId());
                if (ratisServer != null && event.getMember().getRaftPort() > 0) {
                    String peerRaftId = event.getMember().getAddress() + ":" + event.getMember().getRaftPort();
                    ratisServer.addPeer(peerRaftId);
                }
            }
            case LEFT -> {
                log.warn("Member left cluster: {}", event.getMember().getId());
                if (distroProtocol != null) {
                    distroProtocol.onMemberDead(event.getMember());
                }
                if (ratisServer != null && event.getMember().getRaftPort() > 0) {
                    String peerRaftId = event.getMember().getAddress() + ":" + event.getMember().getRaftPort();
                    ratisServer.removePeer(peerRaftId);
                }
            }
            case SUSPECTED -> {
                log.warn("Member suspected: {}", event.getMember().getId());
            }
        }
    }

}
