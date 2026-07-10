package com.x.registry.server.storage;

import com.x.registry.api.model.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstanceStoreTest {

    private static final String NAMESPACE = "public";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String SERVICE_NAME = "test-service";

    private InstanceStore store;

    @BeforeEach
    void setUp() {
        store = new InstanceStore();
    }

    @Test
    void register_and_get() {
        Instance instance = createInstance("10.0.0.1", 8080);

        store.register(NAMESPACE, GROUP, SERVICE_NAME, instance);

        List<Instance> result = store.getInstances(NAMESPACE, GROUP, SERVICE_NAME, false);
        assertEquals(1, result.size());
        assertEquals("10.0.0.1", result.get(0).getIp());
        assertEquals(8080, result.get(0).getPort());
    }

    @Test
    void deregister_removes() {
        Instance instance = createInstance("10.0.0.1", 8080);
        store.register(NAMESPACE, GROUP, SERVICE_NAME, instance);
        assertEquals(1, store.getInstances(NAMESPACE, GROUP, SERVICE_NAME, false).size());

        store.deregister(NAMESPACE, GROUP, SERVICE_NAME, instance);

        List<Instance> result = store.getInstances(NAMESPACE, GROUP, SERVICE_NAME, false);
        assertTrue(result.isEmpty());
    }

    @Test
    void getInstances_healthyOnly_filters() {
        Instance healthy = createInstance("10.0.0.1", 8080);
        Instance unhealthy = createInstance("10.0.0.2", 8081);
        unhealthy.setHealthy(false);

        store.register(NAMESPACE, GROUP, SERVICE_NAME, healthy);
        store.register(NAMESPACE, GROUP, SERVICE_NAME, unhealthy);

        List<Instance> all = store.getInstances(NAMESPACE, GROUP, SERVICE_NAME, false);
        assertEquals(2, all.size());

        List<Instance> healthyOnly = store.getInstances(NAMESPACE, GROUP, SERVICE_NAME, true);
        assertEquals(1, healthyOnly.size());
        assertEquals("10.0.0.1", healthyOnly.get(0).getIp());
    }

    @Test
    void getAllEphemeralInstances_filtersNonEphemeral() {
        Instance ephemeral = createInstance("10.0.0.1", 8080);
        ephemeral.setEphemeral(true);

        Instance persistent = createInstance("10.0.0.2", 8081);
        persistent.setEphemeral(false);

        store.register(NAMESPACE, GROUP, SERVICE_NAME, ephemeral);
        store.register(NAMESPACE, GROUP, SERVICE_NAME, persistent);

        Map<String, Instance> ephemerals = store.getAllEphemeralInstances();
        assertEquals(1, ephemerals.size());
        Instance found = ephemerals.values().iterator().next();
        assertEquals("10.0.0.1", found.getIp());
    }

    @Test
    void getAllPersistentInstances_filtersEphemeral() {
        Instance ephemeral = createInstance("10.0.0.1", 8080);
        ephemeral.setEphemeral(true);

        Instance persistent = createInstance("10.0.0.2", 8081);
        persistent.setEphemeral(false);

        store.register(NAMESPACE, GROUP, SERVICE_NAME, ephemeral);
        store.register(NAMESPACE, GROUP, SERVICE_NAME, persistent);

        Map<String, Instance> persistents = store.getAllPersistentInstances();
        assertEquals(1, persistents.size());
        Instance found = persistents.values().iterator().next();
        assertEquals("10.0.0.2", found.getIp());
    }

    @Test
    void getAllServiceKeys_returnsKeys() {
        Instance inst1 = createInstance("10.0.0.1", 8080);
        Instance inst2 = createInstance("10.0.0.2", 8081);

        store.register(NAMESPACE, GROUP, "service-a", inst1);
        store.register(NAMESPACE, GROUP, "service-b", inst2);

        List<String> keys = store.getAllServiceKeys();
        assertEquals(2, keys.size());
        assertTrue(keys.contains(NAMESPACE + "@@" + GROUP + "@@service-a"));
        assertTrue(keys.contains(NAMESPACE + "@@" + GROUP + "@@service-b"));
    }

    @Test
    void maxInstanceCount_rejectsWhenFull() {
        store.setMaxInstanceCount(2);

        store.register(NAMESPACE, GROUP, SERVICE_NAME, createInstance("10.0.0.1", 8080));
        store.register(NAMESPACE, GROUP, SERVICE_NAME, createInstance("10.0.0.2", 8081));

        assertThrows(IllegalStateException.class, () ->
                store.register(NAMESPACE, GROUP, SERVICE_NAME, createInstance("10.0.0.3", 8082)));
    }

    @Test
    void maxInstanceCount_allowsReRegister() {
        store.setMaxInstanceCount(1);

        Instance instance = createInstance("10.0.0.1", 8080);
        store.register(NAMESPACE, GROUP, SERVICE_NAME, instance);

        // Re-register the same instance (same ip:port:cluster) -- should succeed
        Instance sameInstance = createInstance("10.0.0.1", 8080);
        sameInstance.setWeight(2.0);
        assertDoesNotThrow(() ->
                store.register(NAMESPACE, GROUP, SERVICE_NAME, sameInstance));

        List<Instance> result = store.getInstances(NAMESPACE, GROUP, SERVICE_NAME, false);
        assertEquals(1, result.size());
        assertEquals(2.0, result.get(0).getWeight());
    }

    @Test
    void getInstance_byIpPortCluster() {
        Instance instance = createInstance("10.0.0.1", 8080);
        instance.setClusterName("cluster-1");

        store.register(NAMESPACE, GROUP, SERVICE_NAME, instance);

        Instance found = store.getInstance(NAMESPACE, GROUP, SERVICE_NAME, "10.0.0.1", 8080, "cluster-1");
        assertNotNull(found);
        assertEquals("10.0.0.1", found.getIp());
        assertEquals(8080, found.getPort());
        assertEquals("cluster-1", found.getClusterName());
    }

    @Test
    void removeInstance_byKey() {
        Instance instance = createInstance("10.0.0.1", 8080);
        store.register(NAMESPACE, GROUP, SERVICE_NAME, instance);

        // Build the key the same way InstanceStore does internally
        String serviceKey = NAMESPACE + "@@" + GROUP + "@@" + SERVICE_NAME;
        String instanceKey = serviceKey + "@@" + instance.getIp() + ":" + instance.getPort()
                + "#" + instance.getClusterName();

        assertNotNull(store.getInstanceByKey(instanceKey));

        store.removeInstance(instanceKey);

        assertNull(store.getInstanceByKey(instanceKey));
        assertTrue(store.getInstances(NAMESPACE, GROUP, SERVICE_NAME, false).isEmpty());
    }

    private Instance createInstance(String ip, int port) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setEphemeral(true);
        instance.setHealthy(true);
        instance.setEnabled(true);
        return instance;
    }
}
