package com.x.registry.server.cluster;

import java.util.ArrayList;
import java.util.List;

public class ClusterConfig {

    private boolean clusterEnabled = false;
    private String bindAddress = "127.0.0.1";
    private int gossipPort = 7848;
    private int distroPort = 7849;
    private int raftPort = 7850;
    private int grpcPort = 9848;
    private List<String> seedNodes = new ArrayList<>();

    private String raftDataDir = "./data/raft";

    private long gossipProbeIntervalMs = 1000;
    private long gossipSuspectTimeoutMs = 5000;
    private long distroVerifyIntervalMs = 5000;

    private boolean tlsEnabled = false;
    private String certPath;
    private String keyPath;
    private String trustCertPath;

    public boolean isClusterEnabled() {
        return clusterEnabled;
    }

    public ClusterConfig setClusterEnabled(boolean clusterEnabled) {
        this.clusterEnabled = clusterEnabled;
        return this;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public ClusterConfig setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
        return this;
    }

    public int getGossipPort() {
        return gossipPort;
    }

    public ClusterConfig setGossipPort(int gossipPort) {
        this.gossipPort = gossipPort;
        return this;
    }

    public int getDistroPort() {
        return distroPort;
    }

    public ClusterConfig setDistroPort(int distroPort) {
        this.distroPort = distroPort;
        return this;
    }

    public int getRaftPort() {
        return raftPort;
    }

    public ClusterConfig setRaftPort(int raftPort) {
        this.raftPort = raftPort;
        return this;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public ClusterConfig setGrpcPort(int grpcPort) {
        this.grpcPort = grpcPort;
        return this;
    }

    public List<String> getSeedNodes() {
        return seedNodes;
    }

    public ClusterConfig setSeedNodes(List<String> seedNodes) {
        this.seedNodes = seedNodes;
        return this;
    }

    public String getRaftDataDir() {
        return raftDataDir;
    }

    public ClusterConfig setRaftDataDir(String raftDataDir) {
        this.raftDataDir = raftDataDir;
        return this;
    }

    public long getGossipProbeIntervalMs() {
        return gossipProbeIntervalMs;
    }

    public ClusterConfig setGossipProbeIntervalMs(long gossipProbeIntervalMs) {
        this.gossipProbeIntervalMs = gossipProbeIntervalMs;
        return this;
    }

    public long getGossipSuspectTimeoutMs() {
        return gossipSuspectTimeoutMs;
    }

    public ClusterConfig setGossipSuspectTimeoutMs(long gossipSuspectTimeoutMs) {
        this.gossipSuspectTimeoutMs = gossipSuspectTimeoutMs;
        return this;
    }

    public long getDistroVerifyIntervalMs() {
        return distroVerifyIntervalMs;
    }

    public ClusterConfig setDistroVerifyIntervalMs(long distroVerifyIntervalMs) {
        this.distroVerifyIntervalMs = distroVerifyIntervalMs;
        return this;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public ClusterConfig setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
        return this;
    }

    public String getCertPath() {
        return certPath;
    }

    public ClusterConfig setCertPath(String certPath) {
        this.certPath = certPath;
        return this;
    }

    public String getKeyPath() {
        return keyPath;
    }

    public ClusterConfig setKeyPath(String keyPath) {
        this.keyPath = keyPath;
        return this;
    }

    public String getTrustCertPath() {
        return trustCertPath;
    }

    public ClusterConfig setTrustCertPath(String trustCertPath) {
        this.trustCertPath = trustCertPath;
        return this;
    }
}
