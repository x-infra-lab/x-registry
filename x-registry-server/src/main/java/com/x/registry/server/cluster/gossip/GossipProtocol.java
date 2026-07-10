package com.x.registry.server.cluster.gossip;

import com.x.registry.server.cluster.Member;
import com.x.registry.server.cluster.MemberManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * SWIM-based gossip protocol for cluster member discovery and failure detection.
 *
 * Protocol flow:
 * 1. Every probeInterval, pick a random member and send Ping
 * 2. If Ack received → member is alive
 * 3. If no Ack within timeout → send IndirectPing via K random members
 * 4. If still no response → mark as Suspect
 * 5. After suspectTimeout → mark as Dead
 */
public class GossipProtocol {

    private static final Logger log = LoggerFactory.getLogger(GossipProtocol.class);

    private final MemberManager memberManager;
    private final GossipTransport transport;
    private final GossipConfig config;

    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> suspectTimestamps = new ConcurrentHashMap<>();
    private final Queue<GossipMessage> pendingBroadcasts = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;

    public GossipProtocol(MemberManager memberManager, GossipTransport transport, GossipConfig config) {
        this.memberManager = memberManager;
        this.transport = transport;
        this.config = config;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "gossip-protocol");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        running = true;
        scheduler.scheduleWithFixedDelay(this::probeRound, config.getProbeIntervalMs(),
                config.getProbeIntervalMs(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::checkSuspects, 1000, 1000, TimeUnit.MILLISECONDS);
        log.info("Gossip protocol started, probeInterval={}ms, suspectTimeout={}ms",
                config.getProbeIntervalMs(), config.getSuspectTimeoutMs());
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    public void leave() {
        log.info("Voluntarily leaving cluster");
        Member self = memberManager.getSelf();
        self.setState(Member.State.DEAD);
        GossipMessage deadMsg = GossipMessage.dead(self, self);
        List<Member> targets = memberManager.getAliveMembersExcludingSelf();
        int pingsToSend = Math.min(3, targets.size());
        for (int i = 0; i < pingsToSend; i++) {
            Member target = targets.get(i);
            try {
                GossipMessage ping = GossipMessage.ping(self, List.of(deadMsg));
                transport.sendPing(target.getAddress(), target.getPort(), ping);
            } catch (Exception e) {
                log.debug("Leave notification to {} failed: {}", target.getId(), e.getMessage());
            }
        }
    }

    public void join(List<String> seedNodes) {
        for (String seed : seedNodes) {
            if (seed.equals(memberManager.getSelf().getId())) {
                continue;
            }
            try {
                String[] parts = seed.split(":");
                GossipMessage ping = GossipMessage.ping(memberManager.getSelf(), Collections.emptyList());
                GossipMessage ack = transport.sendPing(parts[0], Integer.parseInt(parts[1]), ping);
                if (ack != null) {
                    processMemberList(ack.getMembers());
                    log.info("Successfully joined cluster via seed: {}", seed);
                }
            } catch (Exception e) {
                log.warn("Failed to join via seed {}: {}", seed, e.getMessage());
            }
        }
    }

    public GossipMessage handlePing(GossipMessage ping) {
        if (ping.getSourceMember() != null) {
            memberManager.addOrUpdate(ping.getSourceMember());
        }
        processMemberList(ping.getMembers());

        List<Member> memberSnapshot = new ArrayList<>(memberManager.getAllMembers());
        return GossipMessage.ack(memberManager.getSelf(), memberSnapshot, getPendingBroadcasts());
    }

    public GossipMessage handleIndirectPing(String targetAddress, int targetPort, GossipMessage ping) {
        GossipMessage ack = transport.sendPing(targetAddress, targetPort, ping);
        return ack;
    }

    private void probeRound() {
        if (!running) return;
        try {
            List<Member> targets = memberManager.getAliveMembersExcludingSelf();
            if (targets.isEmpty()) return;

            Member target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
            probe(target);
        } catch (Exception e) {
            log.error("Probe round error", e);
        }
    }

    private void probe(Member target) {
        List<GossipMessage> broadcasts = getPendingBroadcasts();
        GossipMessage ping = GossipMessage.ping(memberManager.getSelf(), broadcasts);

        GossipMessage ack = transport.sendPingWithTimeout(target.getAddress(), target.getPort(),
                ping, config.getPingTimeoutMs());

        if (ack != null) {
            memberManager.markAlive(target.getId());
            processMemberList(ack.getMembers());
            processBroadcasts(ack.getPiggybackMessages());
            suspectTimestamps.remove(target.getId());
        } else {
            indirectProbe(target);
        }
    }

    private void indirectProbe(Member target) {
        List<Member> others = memberManager.getAliveMembersExcludingSelf().stream()
                .filter(m -> !m.getId().equals(target.getId()))
                .toList();

        int k = Math.min(config.getIndirectProbeCount(), others.size());
        if (k == 0) {
            markSuspect(target);
            return;
        }

        List<Member> probers = new ArrayList<>(others);
        Collections.shuffle(probers);
        probers = probers.subList(0, k);

        boolean gotAck = false;
        for (Member prober : probers) {
            GossipMessage ping = GossipMessage.ping(memberManager.getSelf(), Collections.emptyList());
            GossipMessage ack = transport.sendIndirectPing(prober.getAddress(), prober.getPort(),
                    target.getAddress(), target.getPort(), ping);
            if (ack != null) {
                gotAck = true;
                memberManager.markAlive(target.getId());
                suspectTimestamps.remove(target.getId());
                break;
            }
        }

        if (!gotAck) {
            markSuspect(target);
        }
    }

    private void markSuspect(Member target) {
        memberManager.markSuspect(target.getId());
        suspectTimestamps.putIfAbsent(target.getId(), System.currentTimeMillis());
        broadcastState(GossipMessage.suspect(memberManager.getSelf(), target));
    }

    private void checkSuspects() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = suspectTimestamps.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > config.getSuspectTimeoutMs()) {
                memberManager.markDead(entry.getKey());
                broadcastState(GossipMessage.dead(memberManager.getSelf(),
                        memberManager.getMember(entry.getKey())));
                it.remove();
            }
        }
    }

    private void broadcastState(GossipMessage message) {
        pendingBroadcasts.offer(message);
        while (pendingBroadcasts.size() > 50) {
            pendingBroadcasts.poll();
        }
    }

    private List<GossipMessage> getPendingBroadcasts() {
        List<GossipMessage> messages = new ArrayList<>();
        GossipMessage msg;
        int count = 0;
        while ((msg = pendingBroadcasts.peek()) != null && count < 10) {
            messages.add(msg);
            pendingBroadcasts.poll();
            count++;
        }
        return messages;
    }

    private void processMemberList(List<Member> members) {
        if (members == null) return;
        for (Member member : members) {
            if (!member.getId().equals(memberManager.getSelf().getId())) {
                memberManager.addOrUpdate(member);
            }
        }
    }

    private void processBroadcasts(List<GossipMessage> messages) {
        if (messages == null) return;
        for (GossipMessage msg : messages) {
            switch (msg.getType()) {
                case SUSPECT -> memberManager.markSuspect(msg.getTargetMemberId());
                case DEAD -> memberManager.markDead(msg.getTargetMemberId());
                case ALIVE -> memberManager.markAlive(msg.getTargetMemberId());
                default -> {}
            }
        }
    }
}
