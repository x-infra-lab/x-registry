package com.x.registry.server.naming;

import com.x.registry.api.model.Instance;
import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.storage.InstanceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceManagerTest {

    private static final String NAMESPACE = "public";
    private static final String SERVICE_NAME = "test-service";
    private static final String GROUP = "DEFAULT_GROUP";

    private InstanceStore instanceStore;
    private SubscriberManager subscriberManager;
    private PushAggregator pushAggregator;
    private ClusterManager clusterManager;
    private MeterRegistry meterRegistry;
    private ServiceManager serviceManager;

    @BeforeEach
    void setUp() {
        instanceStore = new InstanceStore();
        subscriberManager = mock(SubscriberManager.class);
        pushAggregator = mock(PushAggregator.class);
        clusterManager = mock(ClusterManager.class);
        meterRegistry = new SimpleMeterRegistry();

        when(clusterManager.isClustered()).thenReturn(false);

        serviceManager = new ServiceManager(instanceStore, subscriberManager,
                pushAggregator, meterRegistry, clusterManager);
    }

    @Test
    void registerInstance_storesTheInstance() {
        Instance instance = createInstance("192.168.1.1", 8080);

        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, instance);

        List<Instance> instances = serviceManager.getInstances(NAMESPACE, SERVICE_NAME, GROUP, false);
        assertEquals(1, instances.size());
        assertEquals("192.168.1.1", instances.get(0).getIp());
        assertEquals(8080, instances.get(0).getPort());
    }

    @Test
    void deregisterInstance_removesTheInstance() {
        Instance instance = createInstance("192.168.1.2", 9090);

        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, instance);
        List<Instance> before = serviceManager.getInstances(NAMESPACE, SERVICE_NAME, GROUP, false);
        assertEquals(1, before.size());

        serviceManager.deregisterInstance(NAMESPACE, SERVICE_NAME, GROUP, instance);
        List<Instance> after = serviceManager.getInstances(NAMESPACE, SERVICE_NAME, GROUP, false);
        assertTrue(after.isEmpty());
    }

    @Test
    void getInstances_returnsRegisteredInstances() {
        Instance inst1 = createInstance("10.0.0.1", 8001);
        Instance inst2 = createInstance("10.0.0.2", 8002);

        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, inst1);
        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, inst2);

        List<Instance> instances = serviceManager.getInstances(NAMESPACE, SERVICE_NAME, GROUP, false);
        assertEquals(2, instances.size());
    }

    @Test
    void getInstances_healthyOnlyFiltersUnhealthyInstances() {
        Instance healthy = createInstance("10.0.0.1", 8001);
        Instance unhealthy = createInstance("10.0.0.2", 8002);

        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, healthy);
        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, unhealthy);

        serviceManager.markUnhealthy(NAMESPACE, GROUP, SERVICE_NAME, unhealthy);

        List<Instance> healthyOnly = serviceManager.getInstances(NAMESPACE, SERVICE_NAME, GROUP, true);
        assertEquals(1, healthyOnly.size());
        assertEquals("10.0.0.1", healthyOnly.get(0).getIp());
    }

    @Test
    void markUnhealthy_marksInstanceAsUnhealthy() {
        Instance instance = createInstance("10.0.0.3", 8003);
        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, instance);

        assertTrue(instance.isHealthy());

        serviceManager.markUnhealthy(NAMESPACE, GROUP, SERVICE_NAME, instance);

        assertFalse(instance.isHealthy());
        verify(pushAggregator, times(2)).markDirty(NAMESPACE, GROUP, SERVICE_NAME);
    }

    @Test
    void markHealthy_restoresInstanceHealth() {
        Instance instance = createInstance("10.0.0.4", 8004);
        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, instance);

        serviceManager.markUnhealthy(NAMESPACE, GROUP, SERVICE_NAME, instance);
        assertFalse(instance.isHealthy());

        serviceManager.markHealthy(NAMESPACE, GROUP, SERVICE_NAME, instance);
        assertTrue(instance.isHealthy());
    }

    @Test
    void registerInstance_notifiesSubscribers() {
        Instance instance = createInstance("10.0.0.5", 8005);

        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, instance);

        verify(pushAggregator).markDirty(NAMESPACE, GROUP, SERVICE_NAME);
    }

    @Test
    void deregisterInstance_notifiesSubscribers() {
        Instance instance = createInstance("10.0.0.6", 8006);
        serviceManager.registerInstance(NAMESPACE, SERVICE_NAME, GROUP, instance);

        serviceManager.deregisterInstance(NAMESPACE, SERVICE_NAME, GROUP, instance);

        verify(pushAggregator, times(2)).markDirty(NAMESPACE, GROUP, SERVICE_NAME);
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
