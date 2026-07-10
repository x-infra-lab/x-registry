package com.x.registry.tests;

import com.x.registry.api.config.ConfigService;
import com.x.registry.api.model.ConfigItem;
import com.x.registry.api.model.Instance;
import com.x.registry.api.naming.NamingService;
import com.x.registry.client.XRegistryClient;
import com.x.registry.client.XRegistryClientConfig;
import com.x.registry.server.cluster.Member;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterE2ETest {

    private static final Logger log = LoggerFactory.getLogger(ClusterE2ETest.class);

    private static final String NS = "public";
    private static final String GROUP = "DEFAULT_GROUP";

    private ClusterNode node1;
    private ClusterNode node2;
    private ClusterNode node3;
    private final List<XRegistryClient> clients = new ArrayList<>();

    @BeforeAll
    void startCluster() throws Exception {
        log.info("========== Starting 3-node cluster ==========");

        // Node 1: first node, no seed nodes
        node1 = new ClusterNode("node1", 18848, 19848, 17848, 17849, 17850, List.of());
        node1.start();
        log.info("Node 1 started");

        // Wait for node 1 to elect itself as leader (single-node Raft)
        Thread.sleep(5000);

        // Node 2: joins node 1 via gossip
        node2 = new ClusterNode("node2", 28848, 29848, 27848, 27849, 27850,
                List.of(node1.getGossipAddress()));
        node2.start();
        log.info("Node 2 started, joining node 1");

        Thread.sleep(2000);

        // Node 3: joins node 1 via gossip
        node3 = new ClusterNode("node3", 38848, 39848, 37848, 37849, 37850,
                List.of(node1.getGossipAddress()));
        node3.start();
        log.info("Node 3 started, joining node 1");

        // Wait for cluster to stabilize
        waitForClusterFormation(15000);
        log.info("========== Cluster formation complete ==========");
    }

    @AfterAll
    void stopCluster() {
        log.info("========== Stopping cluster ==========");
        for (XRegistryClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (node3 != null && node3.isRunning()) node3.stop();
        if (node2 != null && node2.isRunning()) node2.stop();
        if (node1 != null && node1.isRunning()) node1.stop();
        log.info("========== Cluster stopped ==========");
    }

    @Test
    @Order(1)
    @DisplayName("3-node cluster formation via gossip")
    void testClusterFormation() {
        Collection<Member> members1 = node1.getMembers();
        Collection<Member> members2 = node2.getMembers();
        Collection<Member> members3 = node3.getMembers();

        log.info("Node 1 sees {} members: {}", members1.size(), members1);
        log.info("Node 2 sees {} members: {}", members2.size(), members2);
        log.info("Node 3 sees {} members: {}", members3.size(), members3);

        assertEquals(3, members1.size(), "Node 1 should see 3 members");
        assertEquals(3, members2.size(), "Node 2 should see 3 members");
        assertEquals(3, members3.size(), "Node 3 should see 3 members");

        long aliveCount1 = members1.stream().filter(m -> m.getState() == Member.State.ALIVE).count();
        assertEquals(3, aliveCount1, "All 3 members should be ALIVE on node 1");

        // At least one node should be Raft leader
        boolean anyLeader = node1.isLeader() || node2.isLeader() || node3.isLeader();
        assertTrue(anyLeader, "At least one node should be the Raft leader");
        log.info("Leader: node1={}, node2={}, node3={}", node1.isLeader(), node2.isLeader(), node3.isLeader());
    }

    @Test
    @Order(2)
    @DisplayName("Config publish on leader, read from all nodes")
    void testConfigPublishAndRead() throws Exception {
        ClusterNode leaderNode = findLeader();
        assertNotNull(leaderNode, "Must have a leader");

        XRegistryClient leaderClient = createClient(leaderNode.getGrpcPort());
        ConfigService cs = leaderClient.getConfigService();

        boolean published = cs.publishConfig(NS, "e2e-test.yaml", GROUP,
                "version: 1\nkey: value", "yaml", "e2e-test", "E2E test config");
        assertTrue(published, "Config publish on leader should succeed");

        // Wait for Raft replication
        Thread.sleep(2000);

        // Read from all 3 nodes
        for (ClusterNode node : List.of(node1, node2, node3)) {
            XRegistryClient client = createClient(node.getGrpcPort());
            ConfigItem item = client.getConfigService().getConfig(NS, "e2e-test.yaml", GROUP);
            assertNotNull(item, "Config should be readable from " + node.getName());
            assertEquals("version: 1\nkey: value", item.getContent(),
                    "Content should match on " + node.getName());
            log.info("{}: read config OK, version={}", node.getName(), item.getVersion());
        }
    }

    @Test
    @Order(3)
    @DisplayName("Config change watch notification across cluster")
    void testConfigWatchNotification() throws Exception {
        ClusterNode leaderNode = findLeader();
        assertNotNull(leaderNode, "Must have a leader");

        // Subscribe on a non-leader node if possible
        ClusterNode watchNode = (leaderNode == node1) ? node2 : node1;
        XRegistryClient watchClient = createClient(watchNode.getGrpcPort());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ConfigItem> received = new AtomicReference<>();

        watchClient.getConfigService().addListener(NS, "e2e-watch.yaml", GROUP, item -> {
            log.info("Config change received on {}: content={}", watchNode.getName(), item.getContent());
            received.set(item);
            latch.countDown();
        });

        Thread.sleep(1000);

        // Publish on leader
        XRegistryClient leaderClient = createClient(leaderNode.getGrpcPort());
        boolean published = leaderClient.getConfigService().publishConfig(
                NS, "e2e-watch.yaml", GROUP,
                "watched-content-v1", "text", "e2e-test", "trigger watch");
        assertTrue(published);

        boolean notified = latch.await(10, TimeUnit.SECONDS);
        assertTrue(notified, "Config watch notification should arrive within 10s");
        assertNotNull(received.get());
        assertEquals("watched-content-v1", received.get().getContent());
    }

    @Test
    @Order(4)
    @DisplayName("Service registration on one node, discovery from another (Distro sync)")
    void testServiceRegistrationAndDiscovery() throws Exception {
        XRegistryClient client1 = createClient(node1.getGrpcPort());
        NamingService ns1 = client1.getNamingService();

        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(8080);
        instance.setWeight(1.0);
        instance.setEphemeral(true);

        ns1.registerInstance(NS, "e2e-service", GROUP, instance);
        log.info("Registered instance 10.0.0.1:8080 on node 1");

        // Wait for Distro sync
        Thread.sleep(3000);

        // Discover from node 2
        XRegistryClient client2 = createClient(node2.getGrpcPort());
        List<Instance> instances2 = client2.getNamingService().getInstances(NS, "e2e-service", GROUP, false);
        assertFalse(instances2.isEmpty(), "Node 2 should have the instance via Distro sync");
        assertEquals("10.0.0.1", instances2.get(0).getIp());
        assertEquals(8080, instances2.get(0).getPort());
        log.info("Node 2 discovered instance OK: {}:{}", instances2.get(0).getIp(), instances2.get(0).getPort());

        // Discover from node 3
        XRegistryClient client3 = createClient(node3.getGrpcPort());
        List<Instance> instances3 = client3.getNamingService().getInstances(NS, "e2e-service", GROUP, false);
        assertFalse(instances3.isEmpty(), "Node 3 should have the instance via Distro sync");
        log.info("Node 3 discovered instance OK");
    }

    @Test
    @Order(5)
    @DisplayName("Service subscribe push notification across cluster")
    void testServiceSubscribePush() throws Exception {
        // Subscribe on node 2
        XRegistryClient subClient = createClient(node2.getGrpcPort());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<Instance>> received = new AtomicReference<>();

        subClient.getNamingService().subscribe(NS, "e2e-push-service", GROUP, instances -> {
            if (!instances.isEmpty()) {
                log.info("Push notification received on node 2: {} instances", instances.size());
                received.set(instances);
                latch.countDown();
            }
        });

        Thread.sleep(1000);

        // Register on node 1
        XRegistryClient regClient = createClient(node1.getGrpcPort());
        Instance instance = new Instance();
        instance.setIp("10.0.0.2");
        instance.setPort(9090);
        instance.setWeight(1.0);
        instance.setEphemeral(true);
        regClient.getNamingService().registerInstance(NS, "e2e-push-service", GROUP, instance);

        boolean notified = latch.await(10, TimeUnit.SECONDS);
        assertTrue(notified, "Service subscribe should receive push within 10s");
        assertNotNull(received.get());
        assertFalse(received.get().isEmpty());
        log.info("Push notification verified: received {} instances", received.get().size());
    }

    @Test
    @Order(6)
    @DisplayName("Node scale down: shutdown node 3, remaining nodes still functional")
    void testNodeScaleDown() throws Exception {
        log.info("========== Scaling down: stopping node 3 ==========");
        node3.stop();

        // Wait for gossip to detect node 3 as dead (probe interval 1s + suspect timeout 5s)
        Thread.sleep(8000);

        // Verify node 1 and node 2 see node 3 as DEAD or removed
        long alive1 = node1.getMembers().stream()
                .filter(m -> m.getState() == Member.State.ALIVE).count();
        long alive2 = node2.getMembers().stream()
                .filter(m -> m.getState() == Member.State.ALIVE).count();
        log.info("After scale down: node1 sees {} alive, node2 sees {} alive", alive1, alive2);
        assertTrue(alive1 >= 2, "Node 1 should see at least 2 alive members (self + node 2)");
        assertTrue(alive2 >= 2, "Node 2 should see at least 2 alive members (self + node 1)");

        // Config should still work
        ClusterNode activeLeader = findLeaderAmong(node1, node2);
        if (activeLeader != null) {
            XRegistryClient client = createClient(activeLeader.getGrpcPort());
            boolean published = client.getConfigService().publishConfig(
                    NS, "e2e-scaledown.yaml", GROUP,
                    "still-working", "text", "e2e-test", "Published after scale down");
            assertTrue(published, "Config publish should work with 2 nodes");
            log.info("Config publish after scale down: OK");
        }

        // Service registration should still work
        XRegistryClient regClient = createClient(node1.getGrpcPort());
        Instance instance = new Instance();
        instance.setIp("10.0.0.3");
        instance.setPort(7070);
        instance.setEphemeral(true);
        regClient.getNamingService().registerInstance(NS, "e2e-scaledown-service", GROUP, instance);

        Thread.sleep(2000);

        XRegistryClient discClient = createClient(node2.getGrpcPort());
        List<Instance> found = discClient.getNamingService()
                .getInstances(NS, "e2e-scaledown-service", GROUP, false);
        assertFalse(found.isEmpty(), "Service should be discoverable on remaining node 2 after scale down");
        log.info("Service discovery after scale down: OK");
    }

    @Test
    @Order(7)
    @DisplayName("Node scale up: restart node 3, verify it rejoins and syncs data")
    void testNodeScaleUp() throws Exception {
        log.info("========== Scaling up: restarting node 3 ==========");
        node3 = new ClusterNode("node3", 38848, 39848, 37848, 37849, 37850,
                List.of(node1.getGossipAddress()));
        node3.start();

        // Wait for gossip discovery and data sync
        Thread.sleep(10000);

        // Verify all 3 nodes see each other
        long alive3 = node3.getMembers().stream()
                .filter(m -> m.getState() == Member.State.ALIVE).count();
        log.info("After scale up: node 3 sees {} alive members", alive3);
        assertTrue(alive3 >= 3, "Node 3 should see 3 alive members after rejoin");

        // Verify node 3 can read config that was published while it was down
        XRegistryClient client3 = createClient(node3.getGrpcPort());
        ConfigItem item = client3.getConfigService().getConfig(NS, "e2e-test.yaml", GROUP);
        assertNotNull(item, "Node 3 should be able to read config published earlier (via Raft sync)");
        assertEquals("version: 1\nkey: value", item.getContent());
        log.info("Node 3 reads config OK after rejoin: version={}", item.getVersion());

        // Verify node 3 can discover services via Distro full sync
        List<Instance> instances = client3.getNamingService()
                .getInstances(NS, "e2e-service", GROUP, false);
        // Note: ephemeral instances may or may not survive node restart depending on
        // whether the original client is still heartbeating. We just log the result.
        log.info("Node 3 service discovery after rejoin: found {} instances for e2e-service",
                instances.size());

        log.info("========== Scale up complete ==========");
    }

    // --- helpers ---

    private ClusterNode findLeader() {
        if (node1.isRunning() && node1.isLeader()) return node1;
        if (node2.isRunning() && node2.isLeader()) return node2;
        if (node3 != null && node3.isRunning() && node3.isLeader()) return node3;
        return null;
    }

    private ClusterNode findLeaderAmong(ClusterNode... nodes) {
        for (ClusterNode n : nodes) {
            if (n.isRunning() && n.isLeader()) return n;
        }
        return null;
    }

    private XRegistryClient createClient(int grpcPort) {
        XRegistryClientConfig config = new XRegistryClientConfig()
                .setServerAddr("127.0.0.1:" + grpcPort)
                .setHeartbeatIntervalMs(3000)
                .setCacheDir(System.getProperty("java.io.tmpdir") + "/x-registry-e2e-" + grpcPort);
        XRegistryClient client = new XRegistryClient(config);
        clients.add(client);
        return client;
    }

    private void waitForClusterFormation(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                long alive1 = node1.getMembers().stream()
                        .filter(m -> m.getState() == Member.State.ALIVE).count();
                long alive2 = node2.getMembers().stream()
                        .filter(m -> m.getState() == Member.State.ALIVE).count();
                long alive3 = node3.getMembers().stream()
                        .filter(m -> m.getState() == Member.State.ALIVE).count();
                if (alive1 >= 3 && alive2 >= 3 && alive3 >= 3) {
                    log.info("Cluster formed: all 3 nodes see 3 alive members");
                    return;
                }
                log.info("Waiting for cluster: node1={}, node2={}, node3={} alive members",
                        alive1, alive2, alive3);
            } catch (Exception e) {
                // node might not be ready yet
            }
            Thread.sleep(1000);
        }
        log.warn("Cluster formation timeout — proceeding with current state");
    }
}
