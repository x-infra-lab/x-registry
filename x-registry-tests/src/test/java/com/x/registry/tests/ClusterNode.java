package com.x.registry.tests;

import com.x.registry.server.boot.XRegistryServerApplication;
import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.cluster.Member;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.*;

class ClusterNode {

    private final String name;
    private final int httpPort;
    private final int grpcPort;
    private final int gossipPort;
    private final int distroPort;
    private final int raftPort;
    private final List<String> seedNodes;
    private ConfigurableApplicationContext context;

    ClusterNode(String name, int httpPort, int grpcPort,
                int gossipPort, int distroPort, int raftPort,
                List<String> seedNodes) {
        this.name = name;
        this.httpPort = httpPort;
        this.grpcPort = grpcPort;
        this.gossipPort = gossipPort;
        this.distroPort = distroPort;
        this.raftPort = raftPort;
        this.seedNodes = seedNodes != null ? seedNodes : List.of();
    }

    void start() {
        List<String> args = new ArrayList<>();
        args.add("--server.port=" + httpPort);
        args.add("--spring.application.name=x-registry-" + name);
        args.add("--spring.main.allow-bean-definition-overriding=true");
        args.add("--x-registry.grpc.port=" + grpcPort);
        args.add("--x-registry.cluster.enabled=true");
        args.add("--x-registry.cluster.bind-address=127.0.0.1");
        args.add("--x-registry.cluster.gossip-port=" + gossipPort);
        args.add("--x-registry.cluster.distro-port=" + distroPort);
        args.add("--x-registry.cluster.raft-port=" + raftPort);
        args.add("--x-registry.cluster.seed-nodes=" + String.join(",", seedNodes));
        args.add("--x-registry.cluster.raft-data-dir=" + System.getProperty("java.io.tmpdir") + "/x-registry-e2e-raft-" + name);
        args.add("--x-registry.health-check.interval-ms=2000");
        args.add("--x-registry.health-check.unhealthy-threshold-ms=5000");
        args.add("--x-registry.health-check.remove-threshold-ms=10000");
        args.add("--x-registry.storage.type=memory");
        args.add("--x-registry.auth.enabled=false");
        args.add("--logging.level.com.x.registry=INFO");
        args.add("--management.endpoints.web.exposure.include=health");

        SpringApplication app = new SpringApplication(XRegistryServerApplication.class);
        context = app.run(args.toArray(new String[0]));
    }

    void stop() {
        if (context != null && context.isActive()) {
            context.close();
            context = null;
        }
    }

    boolean isRunning() {
        return context != null && context.isActive();
    }

    ClusterManager getClusterManager() {
        return context.getBean(ClusterManager.class);
    }

    int getGrpcPort() {
        return grpcPort;
    }

    int getGossipPort() {
        return gossipPort;
    }

    String getName() {
        return name;
    }

    String getGossipAddress() {
        return "127.0.0.1:" + gossipPort;
    }

    Collection<Member> getMembers() {
        return getClusterManager().getMembers();
    }

    boolean isLeader() {
        return getClusterManager().isLeader();
    }

    @Override
    public String toString() {
        return "ClusterNode{" + name + ", grpc=" + grpcPort + ", gossip=" + gossipPort + "}";
    }
}
