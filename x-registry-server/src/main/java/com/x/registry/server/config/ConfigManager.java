package com.x.registry.server.config;

import com.x.registry.api.model.ConfigItem;
import com.x.registry.api.exception.XRegistryException;
import com.x.registry.server.audit.AuditLogger;
import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.storage.ConfigStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    private final ConfigStore configStore;
    private final ConfigWatcherManager watcherManager;
    private final AuditLogger auditLogger;
    private final ClusterManager clusterManager;
    private final MeterRegistry registry;
    private final Counter publishCounter;
    private final Counter deleteCounter;
    private final Map<String, AtomicLong> configVersionGauges = new ConcurrentHashMap<>();

    public ConfigManager(ConfigStore configStore, ConfigWatcherManager watcherManager,
                         AuditLogger auditLogger, MeterRegistry registry,
                         ClusterManager clusterManager) {
        this.configStore = configStore;
        this.watcherManager = watcherManager;
        this.auditLogger = auditLogger;
        this.clusterManager = clusterManager;
        this.registry = registry;
        this.publishCounter = Counter.builder("x_registry_config_publish_total")
                .description("Total config publications")
                .register(registry);
        this.deleteCounter = Counter.builder("x_registry_config_delete_total")
                .description("Total config deletions")
                .register(registry);
    }

    public ConfigItem getConfig(String namespace, String dataId, String group) {
        return configStore.get(namespace, group, dataId);
    }

    public ConfigItem publishConfig(String namespace, String dataId, String group,
                                    String content, String contentType, String operator, String description) {
        if (clusterManager.isClustered()) {
            // Route through Raft for CP consistency — state machine applies to ConfigStore
            boolean success = clusterManager.proposeConfigPublish(
                    namespace, dataId, group, content, contentType, operator, description).join();
            if (!success) {
                throw XRegistryException.serverError("Failed to publish config via Raft (not leader or commit failed)");
            }
        } else {
            configStore.publish(namespace, group, dataId, content, contentType, operator, description);
        }

        // After Raft commit or direct write, read the result back from store
        ConfigItem item = configStore.get(namespace, group, dataId);
        publishCounter.increment();
        if (item != null) {
            trackConfigVersion(namespace, dataId, item.getVersion());
        }
        log.info("Published config: namespace={}, group={}, dataId={}, version={}",
                namespace, group, dataId, item != null ? item.getVersion() : -1);
        auditLogger.logConfigPublish(namespace, dataId, group, operator);
        if (item != null) {
            watcherManager.notifyWatchers(namespace, group, dataId, item);
        }
        return item;
    }

    public boolean removeConfig(String namespace, String dataId, String group, String operator) {
        boolean removed;
        if (clusterManager.isClustered()) {
            removed = clusterManager.proposeConfigDelete(namespace, dataId, group).join();
        } else {
            removed = configStore.remove(namespace, group, dataId);
        }
        if (removed) {
            deleteCounter.increment();
            log.info("Removed config: namespace={}, group={}, dataId={}", namespace, group, dataId);
            auditLogger.logConfigDelete(namespace, dataId, group, operator);
            watcherManager.notifyWatchersDeleted(namespace, group, dataId);
        }
        return removed;
    }

    public List<ConfigItem> listHistory(String namespace, String dataId, String group, int page, int pageSize) {
        return configStore.listHistory(namespace, group, dataId, page, pageSize);
    }

    public int getHistoryCount(String namespace, String dataId, String group) {
        return configStore.getHistoryCount(namespace, group, dataId);
    }

    public ConfigItem getConfigVersion(String namespace, String dataId, String group, long version) {
        return configStore.getVersion(namespace, group, dataId, version);
    }

    public ConfigItem rollback(String namespace, String dataId, String group, long targetVersion) {
        ConfigItem targetItem = configStore.getVersion(namespace, group, dataId, targetVersion);
        if (targetItem == null) {
            return null;
        }

        // Rollback is a re-publish of old content — route through Raft in cluster mode
        return publishConfig(namespace, dataId, group,
                targetItem.getContent(), targetItem.getContentType(),
                "rollback", "Rollback to version " + targetVersion);
    }

    private void trackConfigVersion(String namespace, String dataId, long version) {
        String key = namespace + "@@" + dataId;
        configVersionGauges.computeIfAbsent(key, k -> {
            AtomicLong holder = new AtomicLong(0);
            Gauge.builder("x_registry_config_version", holder, AtomicLong::doubleValue)
                    .description("Current config version")
                    .tags("namespace", namespace, "dataId", dataId)
                    .register(registry);
            return holder;
        }).set(version);
    }
}
