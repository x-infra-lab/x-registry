package com.x.registry.server.cluster.distro;

import com.x.registry.server.cluster.Member;

public class DistroSyncTask {

    public enum Type {
        INCREMENTAL, FULL
    }

    private final Member target;
    private final DistroData data;
    private final Type type;
    private int retryCount = 0;

    public DistroSyncTask(Member target, DistroData data, Type type) {
        this.target = target;
        this.data = data;
        this.type = type;
    }

    public Member getTarget() {
        return target;
    }

    public DistroData getData() {
        return data;
    }

    public Type getType() {
        return type;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetry() {
        retryCount++;
    }
}
