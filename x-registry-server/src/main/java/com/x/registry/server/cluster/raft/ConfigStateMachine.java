package com.x.registry.server.cluster.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.x.registry.api.model.ConfigItem;
import com.x.registry.api.model.Instance;
import com.x.registry.server.config.ConfigWatcherManager;
import com.x.registry.server.storage.ConfigStore;
import com.x.registry.server.storage.InstanceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raft state machine for config data.
 * Applies committed log entries to the ConfigStore.
 */
public class ConfigStateMachine implements RaftStateMachine {

    private static final Logger log = LoggerFactory.getLogger(ConfigStateMachine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigStore configStore;
    private final InstanceStore instanceStore;
    private ConfigWatcherManager watcherManager;
    private volatile boolean isLeader = false;

    public ConfigStateMachine(ConfigStore configStore) {
        this(configStore, null);
    }

    public ConfigStateMachine(ConfigStore configStore, InstanceStore instanceStore) {
        this.configStore = configStore;
        this.instanceStore = instanceStore;
    }

    public void setWatcherManager(ConfigWatcherManager watcherManager) {
        this.watcherManager = watcherManager;
    }

    @Override
    public void onApply(LogEntry entry) {
        try {
            switch (entry.getType()) {
                case CONFIG_PUBLISH -> {
                    Map<String, String> data = MAPPER.readValue(entry.getData(), Map.class);
                    String ns = data.get("namespace");
                    String group = data.get("group");
                    String dataId = data.get("dataId");
                    configStore.publish(ns, group, dataId,
                            data.get("content"), data.get("contentType"),
                            data.get("operator"), data.get("description"));
                    if (watcherManager != null) {
                        ConfigItem item = configStore.get(ns, group, dataId);
                        if (item != null) {
                            watcherManager.notifyWatchers(ns, group, dataId, item);
                        }
                    }
                    log.debug("Applied CONFIG_PUBLISH: {}/{}/{}", ns, group, dataId);
                }
                case CONFIG_DELETE -> {
                    Map<String, String> data = MAPPER.readValue(entry.getData(), Map.class);
                    String ns = data.get("namespace");
                    String group = data.get("group");
                    String dataId = data.get("dataId");
                    configStore.remove(ns, group, dataId);
                    if (watcherManager != null) {
                        watcherManager.notifyWatchersDeleted(ns, group, dataId);
                    }
                    log.debug("Applied CONFIG_DELETE: {}/{}/{}", ns, group, dataId);
                }
                case INSTANCE_REGISTER_PERSISTENT -> {
                    if (instanceStore != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = MAPPER.readValue(entry.getData(), Map.class);
                        Instance instance = MAPPER.convertValue(data.get("instance"), Instance.class);
                        instanceStore.register(
                                (String) data.get("namespace"),
                                (String) data.get("group"),
                                (String) data.get("serviceName"),
                                instance);
                        log.debug("Applied INSTANCE_REGISTER_PERSISTENT: {}/{}/{}",
                                data.get("namespace"), data.get("group"), data.get("serviceName"));
                    }
                }
                case INSTANCE_DEREGISTER_PERSISTENT -> {
                    if (instanceStore != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = MAPPER.readValue(entry.getData(), Map.class);
                        Instance instance = MAPPER.convertValue(data.get("instance"), Instance.class);
                        instanceStore.deregister(
                                (String) data.get("namespace"),
                                (String) data.get("group"),
                                (String) data.get("serviceName"),
                                instance);
                        log.debug("Applied INSTANCE_DEREGISTER_PERSISTENT: {}/{}/{}",
                                data.get("namespace"), data.get("group"), data.get("serviceName"));
                    }
                }
                default -> log.warn("Unknown log entry type: {}", entry.getType());
            }
        } catch (Exception e) {
            log.error("Failed to apply log entry at index {}", entry.getIndex(), e);
        }
    }

    @Override
    public byte[] onSnapshotSave() {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();

            List<ConfigItem> allConfigs = configStore.listAll();
            List<Map<String, Object>> configEntries = new ArrayList<>();
            for (ConfigItem item : allConfigs) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("namespace", item.getNamespace());
                entry.put("group", item.getGroup());
                entry.put("dataId", item.getDataId());
                entry.put("content", item.getContent());
                entry.put("contentType", item.getContentType());
                entry.put("version", item.getVersion());
                configEntries.add(entry);
            }
            snapshot.put("configs", configEntries);

            if (instanceStore != null) {
                Map<String, Instance> persistent = instanceStore.getAllPersistentInstances();
                List<Map<String, Object>> instanceEntries = new ArrayList<>();
                for (Map.Entry<String, Instance> e : persistent.entrySet()) {
                    String[] parts = e.getKey().split("@@");
                    if (parts.length >= 3) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("namespace", parts[0]);
                        entry.put("group", parts[1]);
                        entry.put("serviceName", parts[2]);
                        entry.put("instance", e.getValue());
                        instanceEntries.add(entry);
                    }
                }
                snapshot.put("persistentInstances", instanceEntries);
            }

            byte[] data = MAPPER.writeValueAsBytes(snapshot);
            log.info("Saved snapshot: {} configs, {} persistent instances, {} bytes",
                    allConfigs.size(),
                    instanceStore != null ? instanceStore.getAllPersistentInstances().size() : 0,
                    data.length);
            return data;
        } catch (Exception e) {
            log.error("Failed to save snapshot", e);
            return new byte[0];
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onSnapshotLoad(byte[] snapshot) {
        try {
            Map<String, Object> snapshotData = MAPPER.readValue(snapshot, Map.class);

            List<Map<String, Object>> configEntries = (List<Map<String, Object>>) snapshotData.get("configs");
            if (configEntries == null) {
                configEntries = MAPPER.readValue(snapshot, List.class);
            }
            for (Map<String, Object> entry : configEntries) {
                configStore.publish(
                        (String) entry.get("namespace"),
                        (String) entry.get("group"),
                        (String) entry.get("dataId"),
                        (String) entry.get("content"),
                        (String) entry.get("contentType"),
                        "snapshot-restore", "Restored from Raft snapshot"
                );
            }

            int instanceCount = 0;
            if (instanceStore != null) {
                List<Map<String, Object>> instanceEntries =
                        (List<Map<String, Object>>) snapshotData.get("persistentInstances");
                if (instanceEntries != null) {
                    for (Map<String, Object> entry : instanceEntries) {
                        Instance instance = MAPPER.convertValue(entry.get("instance"), Instance.class);
                        instanceStore.register(
                                (String) entry.get("namespace"),
                                (String) entry.get("group"),
                                (String) entry.get("serviceName"),
                                instance);
                        instanceCount++;
                    }
                }
            }

            log.info("Loaded snapshot: {} configs, {} persistent instances restored",
                    configEntries.size(), instanceCount);
        } catch (Exception e) {
            log.error("Failed to load snapshot", e);
        }
    }

    @Override
    public void onLeaderStart(long term) {
        isLeader = true;
        log.info("This node is now the leader for term {}", term);
    }

    @Override
    public void onLeaderStop() {
        isLeader = false;
        log.info("This node is no longer the leader");
    }

    public boolean isLeader() {
        return isLeader;
    }
}
