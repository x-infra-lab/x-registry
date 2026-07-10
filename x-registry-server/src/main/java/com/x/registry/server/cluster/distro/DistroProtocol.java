package com.x.registry.server.cluster.distro;

import com.x.registry.api.model.Instance;
import com.x.registry.server.cluster.Member;
import com.x.registry.server.cluster.MemberManager;
import com.x.registry.server.storage.InstanceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Distro protocol for AP data synchronization of ephemeral instances.
 *
 * Each client is assigned a "responsible node" via consistent hashing.
 * The responsible node accepts registrations/heartbeats and async-syncs to peers.
 * All nodes can serve reads from their local copy (AP, eventually consistent).
 */
public class DistroProtocol {

    private static final Logger log = LoggerFactory.getLogger(DistroProtocol.class);

    private final MemberManager memberManager;
    private final InstanceStore instanceStore;
    private final DistroTransport transport;
    private final DistroConfig config;
    private final Timer syncLatencyTimer;

    private final ScheduledExecutorService scheduler;
    private final BlockingQueue<DistroSyncTask> syncQueue = new LinkedBlockingQueue<>(10000);

    private volatile boolean running = false;
    private SyncListener syncListener;

    @FunctionalInterface
    public interface SyncListener {
        void onSyncReceived(String namespace, String group, String serviceName);
    }

    public void setSyncListener(SyncListener listener) {
        this.syncListener = listener;
    }

    public DistroProtocol(MemberManager memberManager, InstanceStore instanceStore,
                          DistroTransport transport, DistroConfig config) {
        this(memberManager, instanceStore, transport, config, null);
    }

    public DistroProtocol(MemberManager memberManager, InstanceStore instanceStore,
                          DistroTransport transport, DistroConfig config,
                          MeterRegistry registry) {
        this.memberManager = memberManager;
        this.instanceStore = instanceStore;
        this.transport = transport;
        this.config = config;
        this.syncLatencyTimer = registry != null
                ? Timer.builder("x_registry_distro_sync_latency_seconds")
                        .description("Distro sync latency")
                        .register(registry)
                : null;
        this.scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "distro-protocol");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        running = true;
        scheduler.submit(this::syncWorker);
        scheduler.scheduleWithFixedDelay(this::verifyTask,
                config.getVerifyIntervalMs(), config.getVerifyIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("Distro protocol started, verifyInterval={}ms", config.getVerifyIntervalMs());
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    /**
     * Determine if this node is responsible for a given client key.
     */
    public boolean isResponsible(String clientKey) {
        List<Member> aliveMembers = memberManager.getAliveMembers();
        if (aliveMembers.size() <= 1) {
            return true;
        }

        aliveMembers.sort(Comparator.comparing(Member::getId));
        int slot = Math.abs(clientKey.hashCode()) % aliveMembers.size();
        return aliveMembers.get(slot).getId().equals(memberManager.getSelf().getId());
    }

    /**
     * Get the responsible node for a given client key.
     */
    public Member getResponsibleNode(String clientKey) {
        List<Member> aliveMembers = memberManager.getAliveMembers();
        if (aliveMembers.isEmpty()) {
            return memberManager.getSelf();
        }
        aliveMembers.sort(Comparator.comparing(Member::getId));
        int slot = Math.abs(clientKey.hashCode()) % aliveMembers.size();
        return aliveMembers.get(slot);
    }

    /**
     * Called after a local registration/deregistration to sync data to other nodes.
     */
    public void syncToOthers(DistroData data) {
        List<Member> targets = memberManager.getAliveMembersExcludingSelf();
        for (Member target : targets) {
            syncQueue.offer(new DistroSyncTask(target, data, DistroSyncTask.Type.INCREMENTAL));
        }
    }

    /**
     * Called when receiving sync data from another node.
     */
    public void onReceiveSync(DistroData data) {
        switch (data.getAction()) {
            case REGISTER -> {
                for (Instance instance : data.getInstances()) {
                    instanceStore.register(data.getNamespace(), data.getGroup(),
                            data.getServiceName(), instance);
                }
            }
            case DEREGISTER -> {
                for (Instance instance : data.getInstances()) {
                    instanceStore.deregister(data.getNamespace(), data.getGroup(),
                            data.getServiceName(), instance);
                }
            }
            case FULL_SYNC -> {
                // For full sync, replace all instances for this service from the source
                for (Instance instance : data.getInstances()) {
                    instanceStore.register(data.getNamespace(), data.getGroup(),
                            data.getServiceName(), instance);
                }
            }
        }
        log.debug("Received distro sync: action={}, service={}, instances={}",
                data.getAction(), data.getServiceName(), data.getInstances().size());
        if (syncListener != null) {
            syncListener.onSyncReceived(data.getNamespace(), data.getGroup(), data.getServiceName());
        }
    }

    /**
     * Request full data sync from another node (called when a new node joins).
     */
    public void requestFullSync(Member from) {
        try {
            List<DistroData> allData = transport.requestFullSync(from.getAddress(), from.getDistroPort());
            if (allData != null) {
                for (DistroData data : allData) {
                    onReceiveSync(data);
                }
                log.info("Full sync completed from {}, received {} service data", from.getId(), allData.size());
            }
        } catch (Exception e) {
            log.error("Full sync from {} failed", from.getId(), e);
        }
    }

    /**
     * Handle a full sync request from another node: return all local responsible data.
     */
    public List<DistroData> handleFullSyncRequest() {
        List<DistroData> result = new ArrayList<>();
        Map<String, Instance> allEphemeral = instanceStore.getAllEphemeralInstances();

        Map<String, List<Instance>> byService = new HashMap<>();
        for (Map.Entry<String, Instance> entry : allEphemeral.entrySet()) {
            String[] parts = entry.getKey().split("@@");
            if (parts.length >= 3) {
                String serviceKey = parts[0] + "@@" + parts[1] + "@@" + parts[2];
                byService.computeIfAbsent(serviceKey, k -> new ArrayList<>()).add(entry.getValue());
            }
        }

        for (Map.Entry<String, List<Instance>> entry : byService.entrySet()) {
            String[] parts = entry.getKey().split("@@");
            DistroData data = new DistroData();
            data.setNamespace(parts[0]);
            data.setGroup(parts[1]);
            data.setServiceName(parts[2]);
            data.setAction(DistroData.Action.FULL_SYNC);
            data.setInstances(entry.getValue());
            result.add(data);
        }

        return result;
    }

    /**
     * Called when a member dies — take over its responsible instances.
     */
    public void onMemberDead(Member deadMember) {
        log.info("Member {} died, reassigning responsible data", deadMember.getId());
        // After member list changes, responsibility is automatically redistributed
        // via the hash function. Other alive nodes that become responsible
        // should already have the data from previous syncs.
    }

    private void syncWorker() {
        while (running) {
            try {
                DistroSyncTask task = syncQueue.poll(1, TimeUnit.SECONDS);
                if (task != null) {
                    executeSync(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Sync worker error", e);
            }
        }
    }

    private void executeSync(DistroSyncTask task) {
        Runnable syncAction = () -> {
            try {
                boolean success = transport.syncData(task.getTarget().getAddress(),
                        task.getTarget().getDistroPort(), task.getData());
                if (!success) {
                    log.warn("Failed to sync data to {}", task.getTarget().getId());
                }
            } catch (Exception e) {
                log.warn("Sync to {} failed: {}", task.getTarget().getId(), e.getMessage());
            }
        };
        if (syncLatencyTimer != null) {
            syncLatencyTimer.record(syncAction);
        } else {
            syncAction.run();
        }
    }

    private void verifyTask() {
        if (!running) return;
        try {
            List<Member> peers = memberManager.getAliveMembersExcludingSelf();
            if (peers.isEmpty()) return;

            String localChecksum = computeChecksum();

            for (Member peer : peers) {
                try {
                    String remoteChecksum = transport.getChecksum(peer.getAddress(), peer.getGrpcPort());
                    if (remoteChecksum != null && !remoteChecksum.equals(localChecksum)) {
                        log.info("Checksum mismatch with {}, triggering sync", peer.getId());
                        requestFullSync(peer);
                    }
                } catch (Exception e) {
                    log.debug("Verify with {} failed: {}", peer.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Verify task error", e);
        }
    }

    private String computeChecksum() {
        Map<String, Instance> all = instanceStore.getAllEphemeralInstances();
        long hash = 0;
        for (Map.Entry<String, Instance> entry : all.entrySet()) {
            hash = hash * 31 + entry.getKey().hashCode();
            hash = hash * 31 + Long.hashCode(entry.getValue().getLastHeartbeat());
        }
        return Long.toHexString(hash);
    }
}
