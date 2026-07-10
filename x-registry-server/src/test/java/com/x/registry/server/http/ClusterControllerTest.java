package com.x.registry.server.http;

import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.cluster.Member;
import com.x.registry.server.cluster.MemberManager;
import com.x.registry.server.cluster.raft.RatisServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClusterControllerTest {

    @Mock
    private ClusterManager clusterManager;

    @Mock
    private MemberManager memberManager;

    @Mock
    private RatisServer ratisServer;

    private ClusterController controller;

    @BeforeEach
    void setUp() {
        controller = new ClusterController(clusterManager);
    }

    @Test
    void health_standalone() {
        when(clusterManager.isClustered()).thenReturn(false);
        Member member = new Member("127.0.0.1", 8080, 9090, 9091, 9092);
        when(clusterManager.getMembers()).thenReturn(Collections.singletonList(member));

        Map<String, Object> result = controller.health().block();

        assertNotNull(result);
        assertEquals("UP", result.get("status"));
        assertEquals("standalone", result.get("mode"));
        assertEquals(1, result.get("memberCount"));
    }

    @Test
    void health_cluster() {
        when(clusterManager.isClustered()).thenReturn(true);
        List<Member> members = Arrays.asList(
                new Member("10.0.0.1", 8080, 9090, 9091, 9092),
                new Member("10.0.0.2", 8080, 9090, 9091, 9092),
                new Member("10.0.0.3", 8080, 9090, 9091, 9092)
        );
        when(clusterManager.getMembers()).thenReturn(members);

        Map<String, Object> result = controller.health().block();

        assertNotNull(result);
        assertEquals("cluster", result.get("mode"));
        assertEquals(3, result.get("memberCount"));
    }

    @Test
    void members_returnsMemberList() {
        List<Member> members = Arrays.asList(
                new Member("10.0.0.1", 8080, 9090, 9091, 9092),
                new Member("10.0.0.2", 8081, 9091, 9092, 9093)
        );
        when(clusterManager.getMembers()).thenReturn(members);

        Map<String, Object> result = controller.members().block();

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> memberList = (List<Map<String, Object>>) result.get("members");
        assertEquals(2, memberList.size());
        assertEquals(2, result.get("total"));
    }

    @Test
    void leader_returnsLeaderInfo() {
        when(clusterManager.getLeaderId()).thenReturn("node1:7850");
        when(clusterManager.isLeader()).thenReturn(true);
        when(clusterManager.isClustered()).thenReturn(true);

        Map<String, Object> result = controller.leader().block();

        assertNotNull(result);
        assertEquals("node1:7850", result.get("leader"));
        assertEquals(true, result.get("isLeader"));
        assertEquals("cluster", result.get("mode"));
    }

    @Test
    void raftStatus_standalone_returnsDisabled() {
        when(clusterManager.getRatisServer()).thenReturn(null);

        Map<String, Object> result = controller.raftStatus().block();

        assertNotNull(result);
        assertEquals("disabled", result.get("status"));
        assertEquals("standalone", result.get("mode"));
    }

    @Test
    void raftStatus_cluster_returnsRaftInfo() {
        when(clusterManager.getRatisServer()).thenReturn(ratisServer);
        when(ratisServer.getNodeId()).thenReturn("10.0.0.1:9092");
        when(ratisServer.isLeader()).thenReturn(true);
        when(ratisServer.getLeaderId()).thenReturn("10.0.0.1:9092");
        when(ratisServer.getCurrentTerm()).thenReturn(5L);
        when(ratisServer.getCommitIndex()).thenReturn(100L);

        Map<String, Object> result = controller.raftStatus().block();

        assertNotNull(result);
        assertEquals("10.0.0.1:9092", result.get("nodeId"));
        assertEquals("LEADER", result.get("role"));
        assertEquals("10.0.0.1:9092", result.get("leaderId"));
        assertEquals(5L, result.get("currentTerm"));
        assertEquals(100L, result.get("commitIndex"));
        assertEquals("active", result.get("status"));
    }

    @Test
    void transferLeadership_success() {
        when(clusterManager.getRatisServer()).thenReturn(ratisServer);
        when(ratisServer.isLeader()).thenReturn(true);

        Map<String, Object> result = controller.transferLeadership("node2:9092").block();

        assertNotNull(result);
        assertEquals(true, result.get("success"));
        verify(ratisServer).transferLeadership("node2:9092");
    }

    @Test
    void transferLeadership_notLeader() {
        when(clusterManager.getRatisServer()).thenReturn(ratisServer);
        when(ratisServer.isLeader()).thenReturn(false);

        Map<String, Object> result = controller.transferLeadership("node2:9092").block();

        assertNotNull(result);
        assertEquals(false, result.get("success"));
        verify(ratisServer, never()).transferLeadership(anyString());
    }

    @Test
    void transferLeadership_noRaft() {
        when(clusterManager.getRatisServer()).thenReturn(null);

        Map<String, Object> result = controller.transferLeadership("node2:9092").block();

        assertNotNull(result);
        assertEquals(false, result.get("success"));
    }

    @Test
    void removeMember_success() {
        Member member = new Member("10.0.0.2", 8080, 9090, 9091, 9092);
        when(clusterManager.getMemberManager()).thenReturn(memberManager);
        when(memberManager.getMember("10.0.0.2:8080")).thenReturn(member);
        when(clusterManager.getRatisServer()).thenReturn(null);

        Map<String, Object> result = controller.removeMember("10.0.0.2:8080").block();

        assertNotNull(result);
        assertEquals(true, result.get("success"));
        verify(memberManager).removeMember("10.0.0.2:8080");
    }

    @Test
    void removeMember_notFound() {
        when(clusterManager.getMemberManager()).thenReturn(memberManager);
        when(memberManager.getMember("unknown:8080")).thenReturn(null);

        Map<String, Object> result = controller.removeMember("unknown:8080").block();

        assertNotNull(result);
        assertEquals(false, result.get("success"));
        verify(memberManager, never()).removeMember(anyString());
    }
}
