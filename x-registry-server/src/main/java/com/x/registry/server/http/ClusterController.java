package com.x.registry.server.http;

import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.cluster.Member;
import com.x.registry.server.cluster.raft.RatisServer;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/cluster")
public class ClusterController {

    private final ClusterManager clusterManager;

    public ClusterController(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of(
                "status", "UP",
                "mode", clusterManager.isClustered() ? "cluster" : "standalone",
                "version", "1.0.0-SNAPSHOT",
                "memberCount", clusterManager.getMembers().size()
        ));
    }

    @GetMapping("/members")
    public Mono<Map<String, Object>> members() {
        List<Map<String, Object>> memberList = clusterManager.getMembers().stream()
                .map(m -> Map.<String, Object>of(
                        "address", m.getAddress() + ":" + m.getPort(),
                        "state", m.getState().name(),
                        "grpcPort", m.getGrpcPort(),
                        "distroPort", m.getDistroPort(),
                        "raftPort", m.getRaftPort()
                ))
                .collect(Collectors.toList());

        return Mono.just(Map.of(
                "members", memberList,
                "total", memberList.size()
        ));
    }

    @GetMapping("/leader")
    public Mono<Map<String, Object>> leader() {
        return Mono.just(Map.of(
                "leader", clusterManager.getLeaderId(),
                "isLeader", clusterManager.isLeader(),
                "mode", clusterManager.isClustered() ? "cluster" : "standalone"
        ));
    }

    @GetMapping("/raft/status")
    public Mono<Map<String, Object>> raftStatus() {
        RatisServer ratisServer = clusterManager.getRatisServer();
        if (ratisServer == null) {
            return Mono.just(Map.of("status", "disabled", "mode", "standalone"));
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeId", ratisServer.getNodeId());
        status.put("role", ratisServer.isLeader() ? "LEADER" : "FOLLOWER");
        status.put("leaderId", ratisServer.getLeaderId());
        status.put("currentTerm", ratisServer.getCurrentTerm());
        status.put("commitIndex", ratisServer.getCommitIndex());
        status.put("status", "active");
        return Mono.just(status);
    }

    @PostMapping("/leader/transfer")
    public Mono<Map<String, Object>> transferLeadership(
            @RequestParam(required = false) String targetId) {
        RatisServer ratisServer = clusterManager.getRatisServer();
        if (ratisServer == null) {
            return Mono.just(Map.of("success", false, "message", "Raft not enabled"));
        }
        if (!ratisServer.isLeader()) {
            return Mono.just(Map.of("success", false, "message", "This node is not the leader"));
        }
        ratisServer.transferLeadership(targetId);
        return Mono.just(Map.of("success", true, "message", "Leadership transfer initiated"));
    }

    @PostMapping("/member/{memberId}/remove")
    public Mono<Map<String, Object>> removeMember(@PathVariable String memberId) {
        Member member = clusterManager.getMemberManager().getMember(memberId);
        if (member == null) {
            return Mono.just(Map.of("success", false, "message", "Member not found: " + memberId));
        }
        clusterManager.getMemberManager().removeMember(memberId);
        RatisServer ratisServer = clusterManager.getRatisServer();
        if (ratisServer != null && ratisServer.isLeader() && member.getRaftPort() > 0) {
            ratisServer.removePeer(member.getAddress() + ":" + member.getRaftPort());
        }
        return Mono.just(Map.of("success", true, "message", "Member removed: " + memberId));
    }
}
