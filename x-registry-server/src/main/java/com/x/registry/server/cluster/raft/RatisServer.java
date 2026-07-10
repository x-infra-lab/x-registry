package com.x.registry.server.cluster.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcFactory;
import org.apache.ratis.protocol.*;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.NetUtils;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class RatisServer {

    private static final Logger log = LoggerFactory.getLogger(RatisServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String nodeId;
    private final RatisConfig config;
    private final ConfigStateMachine configStateMachine;
    private final RatisStateMachineAdapter stateMachineAdapter;

    private RaftServer raftServer;
    private RaftClient raftClient;
    private RaftGroupId raftGroupId;
    private RaftGroup raftGroup;

    private final AtomicReference<Thread> leaderWatcherThread = new AtomicReference<>();
    private volatile boolean running = false;

    public RatisServer(String nodeId, RatisConfig config, ConfigStateMachine configStateMachine) {
        this.nodeId = nodeId;
        this.config = config;
        this.configStateMachine = configStateMachine;
        this.stateMachineAdapter = new RatisStateMachineAdapter(configStateMachine);
    }

    public void start() throws IOException {
        running = true;

        UUID groupUuid = UUID.nameUUIDFromBytes(config.getGroupId().getBytes());
        raftGroupId = RaftGroupId.valueOf(groupUuid);

        List<RaftPeer> peers = config.getInitialMembers().stream()
                .map(addr -> RaftPeer.newBuilder()
                        .setId(RaftPeerId.valueOf(addr))
                        .setAddress(addr)
                        .build())
                .collect(Collectors.toList());

        raftGroup = RaftGroup.valueOf(raftGroupId, peers);

        RaftProperties properties = new RaftProperties();
        RaftServerConfigKeys.setStorageDir(properties, Collections.singletonList(new File(config.getDataDir())));

        TimeDuration electionMin = TimeDuration.valueOf(config.getElectionTimeoutMs(), TimeUnit.MILLISECONDS);
        TimeDuration electionMax = TimeDuration.valueOf(config.getElectionTimeoutMs() * 2, TimeUnit.MILLISECONDS);
        RaftServerConfigKeys.Rpc.setTimeoutMin(properties, electionMin);
        RaftServerConfigKeys.Rpc.setTimeoutMax(properties, electionMax);

        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(properties, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(properties, config.getSnapshotAutoTriggerThreshold());

        int port = Integer.parseInt(nodeId.split(":")[1]);
        GrpcConfigKeys.Server.setPort(properties, port);

        Parameters parameters = new Parameters();

        RaftPeerId selfPeerId = RaftPeerId.valueOf(nodeId);
        raftServer = RaftServer.newBuilder()
                .setServerId(selfPeerId)
                .setGroup(raftGroup)
                .setStateMachine(stateMachineAdapter)
                .setProperties(properties)
                .setParameters(parameters)
                .build();
        raftServer.start();

        raftClient = RaftClient.newBuilder()
                .setRaftGroup(raftGroup)
                .setProperties(properties)
                .setParameters(parameters)
                .build();

        startLeaderWatcher();
        log.info("Ratis server started: nodeId={}, group={}, peers={}", nodeId, config.getGroupId(), peers);
    }

    public void stop() {
        running = false;
        try {
            Thread watcher = leaderWatcherThread.getAndSet(null);
            if (watcher != null) {
                watcher.interrupt();
            }
            if (raftClient != null) {
                raftClient.close();
            }
            if (raftServer != null) {
                raftServer.close();
            }
        } catch (Exception e) {
            log.warn("Error stopping Ratis server", e);
        }
    }

    public CompletableFuture<Boolean> propose(LogEntry.Type type, byte[] data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] entryBytes = MAPPER.writeValueAsBytes(new RatisStateMachineAdapter.RatisLogEntry(type, data));
                RaftClientReply reply = raftClient.io().send(Message.valueOf(ByteString.copyFrom(entryBytes)));
                return reply.isSuccess();
            } catch (IOException e) {
                log.error("Propose failed for type {}", type, e);
                return false;
            }
        });
    }

    public boolean isLeader() {
        try {
            RaftServer.Division division = raftServer.getDivision(raftGroupId);
            return division.getInfo().isLeader();
        } catch (Exception e) {
            return false;
        }
    }

    public String getLeaderId() {
        try {
            RaftServer.Division division = raftServer.getDivision(raftGroupId);
            RaftPeerId leader = division.getInfo().getLeaderId();
            return leader != null ? leader.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public long getCurrentTerm() {
        try {
            RaftServer.Division division = raftServer.getDivision(raftGroupId);
            return division.getInfo().getCurrentTerm();
        } catch (Exception e) {
            return 0;
        }
    }

    public long getCommitIndex() {
        try {
            RaftServer.Division division = raftServer.getDivision(raftGroupId);
            return division.getInfo().getLastAppliedIndex();
        } catch (Exception e) {
            return 0;
        }
    }

    public void addPeer(String peerId) {
        try {
            RaftPeer newPeer = RaftPeer.newBuilder()
                    .setId(RaftPeerId.valueOf(peerId))
                    .setAddress(peerId)
                    .build();

            List<RaftPeer> currentPeers = new ArrayList<>(raftGroup.getPeers());
            if (currentPeers.stream().anyMatch(p -> p.getId().toString().equals(peerId))) {
                return;
            }
            currentPeers.add(newPeer);
            raftGroup = RaftGroup.valueOf(raftGroupId, currentPeers);

            if (isLeader()) {
                try (RaftClient adminClient = buildClient()) {
                    adminClient.admin().setConfiguration(currentPeers);
                }
                log.info("Raft peer added via setConfiguration: {}", peerId);
            }
        } catch (Exception e) {
            log.warn("Failed to add Raft peer {}: {}", peerId, e.getMessage());
        }
    }

    public void removePeer(String peerId) {
        try {
            List<RaftPeer> currentPeers = raftGroup.getPeers().stream()
                    .filter(p -> !p.getId().toString().equals(peerId))
                    .collect(Collectors.toList());
            if (currentPeers.size() == raftGroup.getPeers().size()) {
                return;
            }
            raftGroup = RaftGroup.valueOf(raftGroupId, currentPeers);

            if (isLeader()) {
                try (RaftClient adminClient = buildClient()) {
                    adminClient.admin().setConfiguration(currentPeers);
                }
                log.info("Raft peer removed via setConfiguration: {}", peerId);
            }
        } catch (Exception e) {
            log.warn("Failed to remove Raft peer {}: {}", peerId, e.getMessage());
        }
    }

    public void transferLeadership(String targetId) {
        try {
            if (!isLeader()) {
                log.warn("Cannot transfer leadership: not the leader");
                return;
            }
            try (RaftClient adminClient = buildClient()) {
                RaftPeerId target = targetId != null ?
                        RaftPeerId.valueOf(targetId) :
                        raftGroup.getPeers().stream()
                                .filter(p -> !p.getId().toString().equals(nodeId))
                                .findFirst()
                                .map(RaftPeer::getId)
                                .orElse(null);
                if (target != null) {
                    adminClient.admin().transferLeadership(target, 5000);
                    log.info("Leadership transfer initiated to {}", target);
                }
            }
        } catch (Exception e) {
            log.warn("Leadership transfer failed: {}", e.getMessage());
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    private RaftClient buildClient() {
        RaftProperties properties = new RaftProperties();
        Parameters parameters = new Parameters();
        return RaftClient.newBuilder()
                .setRaftGroup(raftGroup)
                .setProperties(properties)
                .setParameters(parameters)
                .build();
    }

    private void startLeaderWatcher() {
        Thread thread = new Thread(() -> {
            boolean wasLeader = false;
            while (running) {
                try {
                    Thread.sleep(500);
                    boolean nowLeader = isLeader();
                    if (nowLeader && !wasLeader) {
                        stateMachineAdapter.notifyLeaderChanged(true, getCurrentTerm());
                    } else if (!nowLeader && wasLeader) {
                        stateMachineAdapter.notifyLeaderChanged(false, getCurrentTerm());
                    }
                    wasLeader = nowLeader;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("Leader watcher error: {}", e.getMessage());
                }
            }
        }, "ratis-leader-watcher-" + nodeId);
        thread.setDaemon(true);
        leaderWatcherThread.set(thread);
        thread.start();
    }
}
