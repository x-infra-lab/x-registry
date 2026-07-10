package com.x.registry.server.cluster.distro;

public class DistroConfig {

    private long verifyIntervalMs = 5000;
    private long syncTimeoutMs = 3000;
    private int syncRetryCount = 3;

    public long getVerifyIntervalMs() {
        return verifyIntervalMs;
    }

    public DistroConfig setVerifyIntervalMs(long verifyIntervalMs) {
        this.verifyIntervalMs = verifyIntervalMs;
        return this;
    }

    public long getSyncTimeoutMs() {
        return syncTimeoutMs;
    }

    public DistroConfig setSyncTimeoutMs(long syncTimeoutMs) {
        this.syncTimeoutMs = syncTimeoutMs;
        return this;
    }

    public int getSyncRetryCount() {
        return syncRetryCount;
    }

    public DistroConfig setSyncRetryCount(int syncRetryCount) {
        this.syncRetryCount = syncRetryCount;
        return this;
    }
}
