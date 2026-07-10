package com.x.registry.server.naming;

import com.x.registry.api.model.Instance;
import com.x.registry.server.storage.InstanceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushAggregatorTest {

    @Mock
    private InstanceStore instanceStore;

    @Mock
    private SubscriberManager subscriberManager;

    private PushAggregator aggregator;

    @BeforeEach
    void setUp() throws Exception {
        aggregator = new PushAggregator(instanceStore, subscriberManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        aggregator.destroy();
    }

    @Test
    void markDirty_triggersNotification() throws Exception {
        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(8080);
        List<Instance> instances = Collections.singletonList(instance);

        when(instanceStore.getInstances("public", "DEFAULT_GROUP", "my-service", false))
                .thenReturn(instances);

        aggregator.afterPropertiesSet();
        aggregator.markDirty("public", "DEFAULT_GROUP", "my-service");

        Thread.sleep(300);

        verify(subscriberManager, atLeastOnce()).notify("public", "DEFAULT_GROUP", "my-service", instances);
    }

    @Test
    void markDirty_deduplicates() throws Exception {
        Instance instance = new Instance();
        instance.setIp("10.0.0.2");
        instance.setPort(9090);
        List<Instance> instances = Collections.singletonList(instance);

        when(instanceStore.getInstances("public", "DEFAULT_GROUP", "dedup-service", false))
                .thenReturn(instances);

        aggregator.afterPropertiesSet();

        aggregator.markDirty("public", "DEFAULT_GROUP", "dedup-service");
        aggregator.markDirty("public", "DEFAULT_GROUP", "dedup-service");
        aggregator.markDirty("public", "DEFAULT_GROUP", "dedup-service");

        Thread.sleep(300);

        verify(subscriberManager, atMost(1)).notify(
                eq("public"), eq("DEFAULT_GROUP"), eq("dedup-service"), anyList());
    }

    @Test
    void markDirty_multipleServices() throws Exception {
        Instance inst1 = new Instance();
        inst1.setIp("10.0.0.1");
        inst1.setPort(8080);
        List<Instance> instances1 = Collections.singletonList(inst1);

        Instance inst2 = new Instance();
        inst2.setIp("10.0.0.2");
        inst2.setPort(9090);
        List<Instance> instances2 = Collections.singletonList(inst2);

        when(instanceStore.getInstances("public", "DEFAULT_GROUP", "svc-a", false))
                .thenReturn(instances1);
        when(instanceStore.getInstances("dev", "DEFAULT_GROUP", "svc-b", false))
                .thenReturn(instances2);

        aggregator.afterPropertiesSet();

        aggregator.markDirty("public", "DEFAULT_GROUP", "svc-a");
        aggregator.markDirty("dev", "DEFAULT_GROUP", "svc-b");

        Thread.sleep(300);

        verify(subscriberManager, atLeastOnce()).notify("public", "DEFAULT_GROUP", "svc-a", instances1);
        verify(subscriberManager, atLeastOnce()).notify("dev", "DEFAULT_GROUP", "svc-b", instances2);
    }
}
