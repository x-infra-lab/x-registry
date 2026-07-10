package com.x.registry.server.cluster.distro;

import com.x.registry.api.model.Instance;
import com.x.registry.server.cluster.Member;
import com.x.registry.server.cluster.MemberManager;
import com.x.registry.server.storage.InstanceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistroProtocolTest {

    @Mock
    private DistroTransport transport;

    @Mock
    private InstanceStore instanceStore;

    private MemberManager memberManager;
    private DistroConfig config;
    private DistroProtocol distroProtocol;

    private Member selfMember;

    @BeforeEach
    void setUp() {
        selfMember = new Member("127.0.0.1", 7848, 9848, 7849, 7850);
        memberManager = new MemberManager(selfMember);
        config = new DistroConfig()
                .setVerifyIntervalMs(5000)
                .setSyncTimeoutMs(3000)
                .setSyncRetryCount(3);
        distroProtocol = new DistroProtocol(memberManager, instanceStore, transport, config);
    }

    @Test
    void isResponsible_singleNode_alwaysTrue() {
        // Only self in the memberManager
        assertTrue(distroProtocol.isResponsible("any-client-key"),
                "Single node should always be responsible");
        assertTrue(distroProtocol.isResponsible("another-key"),
                "Single node should always be responsible for any key");
    }

    @Test
    void isResponsible_multipleNodes_deterministicSlotting() {
        Member peer1 = new Member("10.0.0.2", 7848, 9848, 7849, 7850);
        Member peer2 = new Member("10.0.0.3", 7848, 9848, 7849, 7850);
        memberManager.addOrUpdate(peer1);
        memberManager.addOrUpdate(peer2);

        String testKey = "test-service-key";

        // Call twice with the same key - result must be deterministic
        boolean firstResult = distroProtocol.isResponsible(testKey);
        boolean secondResult = distroProtocol.isResponsible(testKey);
        assertEquals(firstResult, secondResult,
                "isResponsible should return the same result for the same key");

        // Verify that the responsible node can be determined
        Member responsible = distroProtocol.getResponsibleNode(testKey);
        assertNotNull(responsible, "Responsible node should not be null");
    }

    @Test
    void onReceiveSync_register_addsInstances() {
        Instance instance = createInstance("192.168.1.1", 8080);
        DistroData data = DistroData.register("public", "DEFAULT_GROUP", "my-service",
                List.of(instance), "10.0.0.2:7848");

        distroProtocol.onReceiveSync(data);

        verify(instanceStore).register(eq("public"), eq("DEFAULT_GROUP"),
                eq("my-service"), eq(instance));
    }

    @Test
    void onReceiveSync_deregister_removesInstances() {
        Instance instance = createInstance("192.168.1.1", 8080);
        DistroData data = DistroData.deregister("public", "DEFAULT_GROUP", "my-service",
                List.of(instance), "10.0.0.2:7848");

        distroProtocol.onReceiveSync(data);

        verify(instanceStore).deregister(eq("public"), eq("DEFAULT_GROUP"),
                eq("my-service"), eq(instance));
    }

    @Test
    void onReceiveSync_callsSyncListener() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        distroProtocol.setSyncListener((namespace, group, serviceName) -> {
            assertEquals("public", namespace);
            assertEquals("DEFAULT_GROUP", group);
            assertEquals("my-service", serviceName);
            listenerCalled.set(true);
        });

        Instance instance = createInstance("192.168.1.1", 8080);
        DistroData data = DistroData.register("public", "DEFAULT_GROUP", "my-service",
                List.of(instance), "10.0.0.2:7848");

        distroProtocol.onReceiveSync(data);

        assertTrue(listenerCalled.get(), "SyncListener should have been called");
    }

    @Test
    void handleFullSyncRequest_returnsServiceData() {
        Instance instance = createInstance("192.168.1.1", 8080);
        instance.setEphemeral(true);

        Map<String, Instance> ephemeralInstances = new HashMap<>();
        ephemeralInstances.put("public@@DEFAULT_GROUP@@my-service@@192.168.1.1:8080#DEFAULT", instance);
        when(instanceStore.getAllEphemeralInstances()).thenReturn(ephemeralInstances);

        List<DistroData> result = distroProtocol.handleFullSyncRequest();

        assertNotNull(result);
        assertEquals(1, result.size());
        DistroData data = result.get(0);
        assertEquals("public", data.getNamespace());
        assertEquals("DEFAULT_GROUP", data.getGroup());
        assertEquals("my-service", data.getServiceName());
        assertEquals(DistroData.Action.FULL_SYNC, data.getAction());
        assertEquals(1, data.getInstances().size());
    }

    @Test
    void requestFullSync_appliesReceivedData() {
        Member source = new Member("10.0.0.2", 7848, 9848, 7849, 7850);
        memberManager.addOrUpdate(source);

        Instance instance = createInstance("192.168.1.1", 8080);
        DistroData syncData = DistroData.register("public", "DEFAULT_GROUP", "my-service",
                List.of(instance), "10.0.0.2:7848");
        syncData.setAction(DistroData.Action.FULL_SYNC);

        when(transport.requestFullSync(eq("10.0.0.2"), eq(7849))).thenReturn(List.of(syncData));

        distroProtocol.requestFullSync(source);

        verify(instanceStore).register(eq("public"), eq("DEFAULT_GROUP"),
                eq("my-service"), eq(instance));
    }

    @Test
    void syncToOthers_queuesSync() {
        Member peer1 = new Member("10.0.0.2", 7848, 9848, 7849, 7850);
        Member peer2 = new Member("10.0.0.3", 7848, 9848, 7849, 7850);
        memberManager.addOrUpdate(peer1);
        memberManager.addOrUpdate(peer2);

        Instance instance = createInstance("192.168.1.1", 8080);
        DistroData data = DistroData.register("public", "DEFAULT_GROUP", "my-service",
                List.of(instance), selfMember.getId());

        // Should not throw
        assertDoesNotThrow(() -> distroProtocol.syncToOthers(data),
                "syncToOthers should not throw when peers are available");
    }

    private Instance createInstance(String ip, int port) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setEphemeral(true);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setClusterName("DEFAULT");
        instance.setServiceName("my-service");
        return instance;
    }
}
