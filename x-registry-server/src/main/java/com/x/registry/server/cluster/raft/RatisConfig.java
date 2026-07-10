package com.x.registry.server.cluster.raft;

import java.util.ArrayList;
import java.util.List;

public class RatisConfig {

    private String dataDir = "./data/raft";
    private String groupId = "x-registry-config";
    private List<String> initialMembers = new ArrayList<>();
    private long electionTimeoutMs = 3000;
    private long heartbeatIntervalMs = 1000;
    private long snapshotAutoTriggerThreshold = 10000;

    public String getDataDir() {
        return dataDir;
    }

    public RatisConfig setDataDir(String dataDir) {
        this.dataDir = dataDir;
        return this;
    }

    public String getGroupId() {
        return groupId;
    }

    public RatisConfig setGroupId(String groupId) {
        this.groupId = groupId;
        return this;
    }

    public List<String> getInitialMembers() {
        return initialMembers;
    }

    public RatisConfig setInitialMembers(List<String> initialMembers) {
        this.initialMembers = initialMembers;
        return this;
    }

    public long getElectionTimeoutMs() {
        return electionTimeoutMs;
    }

    public RatisConfig setElectionTimeoutMs(long electionTimeoutMs) {
        this.electionTimeoutMs = electionTimeoutMs;
        return this;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public RatisConfig setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        return this;
    }

    public long getSnapshotAutoTriggerThreshold() {
        return snapshotAutoTriggerThreshold;
    }

    public RatisConfig setSnapshotAutoTriggerThreshold(long snapshotAutoTriggerThreshold) {
        this.snapshotAutoTriggerThreshold = snapshotAutoTriggerThreshold;
        return this;
    }
}
