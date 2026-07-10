package com.x.registry.server.naming;

import com.x.registry.api.model.Instance;
import com.x.registry.server.storage.InstanceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Aggregates service change notifications within a 100ms window.
 * Multiple rapid changes to the same service result in a single push.
 */
@Component
public class PushAggregator implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PushAggregator.class);
    private static final long FLUSH_INTERVAL_MS = 100;

    private final InstanceStore instanceStore;
    private final SubscriberManager subscriberManager;
    private final Set<String> dirtyServices = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;

    public PushAggregator(InstanceStore instanceStore, SubscriberManager subscriberManager) {
        this.instanceStore = instanceStore;
        this.subscriberManager = subscriberManager;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "push-aggregator");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public void markDirty(String namespace, String group, String serviceName) {
        dirtyServices.add(namespace + "@@" + group + "@@" + serviceName);
    }

    private void flush() {
        if (dirtyServices.isEmpty()) {
            return;
        }

        Set<String> snapshot = Set.copyOf(dirtyServices);
        dirtyServices.removeAll(snapshot);

        for (String key : snapshot) {
            try {
                String[] parts = key.split("@@");
                String namespace = parts[0];
                String group = parts[1];
                String serviceName = parts[2];

                List<Instance> instances = instanceStore.getInstances(namespace, group, serviceName, false);
                subscriberManager.notify(namespace, group, serviceName, instances);
            } catch (Exception e) {
                log.error("Failed to flush push for {}", key, e);
            }
        }
    }
}
