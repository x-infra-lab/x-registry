package com.x.registry.server.cluster.gossip;

public class GossipConfig {

    private long probeIntervalMs = 1000;
    private long pingTimeoutMs = 500;
    private long suspectTimeoutMs = 5000;
    private int indirectProbeCount = 3;
    private int gossipPort = 7848;

    public long getProbeIntervalMs() {
        return probeIntervalMs;
    }

    public GossipConfig setProbeIntervalMs(long probeIntervalMs) {
        this.probeIntervalMs = probeIntervalMs;
        return this;
    }

    public long getPingTimeoutMs() {
        return pingTimeoutMs;
    }

    public GossipConfig setPingTimeoutMs(long pingTimeoutMs) {
        this.pingTimeoutMs = pingTimeoutMs;
        return this;
    }

    public long getSuspectTimeoutMs() {
        return suspectTimeoutMs;
    }

    public GossipConfig setSuspectTimeoutMs(long suspectTimeoutMs) {
        this.suspectTimeoutMs = suspectTimeoutMs;
        return this;
    }

    public int getIndirectProbeCount() {
        return indirectProbeCount;
    }

    public GossipConfig setIndirectProbeCount(int indirectProbeCount) {
        this.indirectProbeCount = indirectProbeCount;
        return this;
    }

    public int getGossipPort() {
        return gossipPort;
    }

    public GossipConfig setGossipPort(int gossipPort) {
        this.gossipPort = gossipPort;
        return this;
    }
}
