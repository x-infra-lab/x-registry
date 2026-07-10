package com.x.registry.server.storage;

import com.x.registry.api.model.ConfigItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ConfigStore {

    private static final int MAX_HISTORY_SIZE = 30;

    private final Map<String, ConfigItem> configs = new ConcurrentHashMap<>();
    private final Map<String, LinkedList<ConfigItem>> history = new ConcurrentHashMap<>();
    private final AtomicLong versionGenerator = new AtomicLong(0);

    public ConfigItem get(String namespace, String group, String dataId) {
        return configs.get(buildKey(namespace, group, dataId));
    }

    public ConfigItem publish(String namespace, String group, String dataId,
                              String content, String contentType, String operator, String description) {
        String key = buildKey(namespace, group, dataId);

        ConfigItem item = new ConfigItem(namespace, group, dataId);
        item.setContent(content);
        item.setContentType(contentType != null ? contentType : "text");
        item.setOperator(operator);
        item.setDescription(description);
        item.setVersion(versionGenerator.incrementAndGet());
        item.setLastModified(System.currentTimeMillis());

        configs.put(key, item);
        addToHistory(key, item);

        return item;
    }

    public boolean remove(String namespace, String group, String dataId) {
        String key = buildKey(namespace, group, dataId);
        return configs.remove(key) != null;
    }

    public List<ConfigItem> listHistory(String namespace, String group, String dataId, int page, int pageSize) {
        String key = buildKey(namespace, group, dataId);
        LinkedList<ConfigItem> items = history.get(key);
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        synchronized (items) {
            int total = items.size();
            int from = page * pageSize;
            if (from >= total) {
                return Collections.emptyList();
            }
            int to = Math.min(from + pageSize, total);
            return new ArrayList<>(items.subList(from, to));
        }
    }

    public int getHistoryCount(String namespace, String group, String dataId) {
        String key = buildKey(namespace, group, dataId);
        LinkedList<ConfigItem> items = history.get(key);
        return items != null ? items.size() : 0;
    }

    public ConfigItem rollback(String namespace, String group, String dataId, long targetVersion) {
        String key = buildKey(namespace, group, dataId);
        LinkedList<ConfigItem> items = history.get(key);
        if (items == null) {
            return null;
        }

        ConfigItem target = null;
        synchronized (items) {
            for (ConfigItem item : items) {
                if (item.getVersion() == targetVersion) {
                    target = item;
                    break;
                }
            }
        }

        if (target == null) {
            return null;
        }

        return publish(namespace, group, dataId, target.getContent(),
                target.getContentType(), "rollback", "Rollback to version " + targetVersion);
    }

    private void addToHistory(String key, ConfigItem item) {
        history.computeIfAbsent(key, k -> new LinkedList<>());
        LinkedList<ConfigItem> items = history.get(key);
        synchronized (items) {
            items.addFirst(copyOf(item));
            while (items.size() > MAX_HISTORY_SIZE) {
                items.removeLast();
            }
        }
    }

    private ConfigItem copyOf(ConfigItem src) {
        ConfigItem copy = new ConfigItem(src.getNamespace(), src.getGroup(), src.getDataId());
        copy.setContent(src.getContent());
        copy.setContentType(src.getContentType());
        copy.setMd5(src.getMd5());
        copy.setVersion(src.getVersion());
        copy.setLastModified(src.getLastModified());
        copy.setOperator(src.getOperator());
        copy.setDescription(src.getDescription());
        return copy;
    }

    public List<ConfigItem> listAll() {
        return new ArrayList<>(configs.values());
    }

    public ConfigItem getVersion(String namespace, String group, String dataId, long version) {
        String key = buildKey(namespace, group, dataId);
        LinkedList<ConfigItem> items = history.get(key);
        if (items == null) return null;
        synchronized (items) {
            for (ConfigItem item : items) {
                if (item.getVersion() == version) {
                    return item;
                }
            }
        }
        return null;
    }

    private String buildKey(String namespace, String group, String dataId) {
        return namespace + "@@" + group + "@@" + dataId;
    }
}
