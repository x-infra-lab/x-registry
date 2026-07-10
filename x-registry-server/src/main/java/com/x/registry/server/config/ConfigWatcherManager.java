package com.x.registry.server.config;

import com.x.registry.api.model.ConfigItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

@Component
public class ConfigWatcherManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcherManager.class);

    private final Map<String, Set<Consumer<ConfigItem>>> watchers = new ConcurrentHashMap<>();

    public void addWatcher(String namespace, String group, String dataId, Consumer<ConfigItem> watcher) {
        String key = buildKey(namespace, group, dataId);
        watchers.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(watcher);
        log.debug("Added config watcher for {}/{}/{}", namespace, group, dataId);
    }

    public void removeWatcher(String namespace, String group, String dataId, Consumer<ConfigItem> watcher) {
        String key = buildKey(namespace, group, dataId);
        Set<Consumer<ConfigItem>> listeners = watchers.get(key);
        if (listeners != null) {
            listeners.remove(watcher);
        }
    }

    public void notifyWatchers(String namespace, String group, String dataId, ConfigItem item) {
        String key = buildKey(namespace, group, dataId);
        Set<Consumer<ConfigItem>> listeners = watchers.get(key);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }

        for (Consumer<ConfigItem> listener : listeners) {
            try {
                listener.accept(item);
            } catch (Exception e) {
                log.error("Failed to notify config watcher for {}/{}/{}", namespace, group, dataId, e);
            }
        }
    }

    public void notifyWatchersDeleted(String namespace, String group, String dataId) {
        ConfigItem deleted = new ConfigItem(namespace, group, dataId);
        deleted.setContent("");
        deleted.setVersion(-1);
        notifyWatchers(namespace, group, dataId, deleted);
    }

    public int getWatcherCount(String namespace, String group, String dataId) {
        String key = buildKey(namespace, group, dataId);
        Set<Consumer<ConfigItem>> listeners = watchers.get(key);
        return listeners != null ? listeners.size() : 0;
    }

    private String buildKey(String namespace, String group, String dataId) {
        return namespace + "@@" + group + "@@" + dataId;
    }
}
