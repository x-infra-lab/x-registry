package com.x.registry.client.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.x.registry.api.model.ConfigItem;
import com.x.registry.api.model.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local disk cache for service instances and config data.
 * Provides failover when the server is unavailable.
 */
public class LocalCacheManager {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String cacheDir;
    private final Map<String, List<Instance>> instanceCache = new ConcurrentHashMap<>();
    private final Map<String, ConfigItem> configCache = new ConcurrentHashMap<>();

    public LocalCacheManager(String cacheDir) {
        this.cacheDir = cacheDir;
        ensureCacheDir();
        loadFromDisk();
    }

    public void cacheInstances(String namespace, String group, String serviceName, List<Instance> instances) {
        String key = buildServiceKey(namespace, group, serviceName);
        instanceCache.put(key, instances);
        persistInstancesToDisk(key, instances);
    }

    public List<Instance> getCachedInstances(String namespace, String group, String serviceName) {
        String key = buildServiceKey(namespace, group, serviceName);
        return instanceCache.getOrDefault(key, Collections.emptyList());
    }

    public void cacheConfig(String namespace, String group, String dataId, ConfigItem item) {
        String key = buildConfigKey(namespace, group, dataId);
        configCache.put(key, item);
        persistConfigToDisk(key, item);
    }

    public ConfigItem getCachedConfig(String namespace, String group, String dataId) {
        String key = buildConfigKey(namespace, group, dataId);
        return configCache.get(key);
    }

    private void persistInstancesToDisk(String key, List<Instance> instances) {
        try {
            File file = new File(cacheDir, "naming_" + sanitize(key) + ".json");
            MAPPER.writeValue(file, instances);
        } catch (IOException e) {
            log.warn("Failed to persist instance cache for {}: {}", key, e.getMessage());
        }
    }

    private void persistConfigToDisk(String key, ConfigItem item) {
        try {
            File file = new File(cacheDir, "config_" + sanitize(key) + ".json");
            MAPPER.writeValue(file, item);
        } catch (IOException e) {
            log.warn("Failed to persist config cache for {}: {}", key, e.getMessage());
        }
    }

    private void loadFromDisk() {
        File dir = new File(cacheDir);
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            try {
                String name = file.getName();
                if (name.startsWith("naming_") && name.endsWith(".json")) {
                    String key = desanitize(name.substring(7, name.length() - 5));
                    List<Instance> instances = MAPPER.readValue(file, new TypeReference<>() {});
                    instanceCache.put(key, instances);
                } else if (name.startsWith("config_") && name.endsWith(".json")) {
                    String key = desanitize(name.substring(7, name.length() - 5));
                    ConfigItem item = MAPPER.readValue(file, ConfigItem.class);
                    configCache.put(key, item);
                }
            } catch (IOException e) {
                log.warn("Failed to load cache file {}: {}", file.getName(), e.getMessage());
            }
        }

        if (!instanceCache.isEmpty() || !configCache.isEmpty()) {
            log.info("Loaded local cache: {} service entries, {} config entries",
                    instanceCache.size(), configCache.size());
        }
    }

    private void ensureCacheDir() {
        try {
            Files.createDirectories(Path.of(cacheDir));
        } catch (IOException e) {
            log.warn("Failed to create cache directory: {}", cacheDir);
        }
    }

    private String buildServiceKey(String namespace, String group, String serviceName) {
        return namespace + "@@" + group + "@@" + serviceName;
    }

    private String buildConfigKey(String namespace, String group, String dataId) {
        return namespace + "@@" + group + "@@" + dataId;
    }

    private String sanitize(String key) {
        return key.replace("@@", "__");
    }

    private String desanitize(String key) {
        return key.replace("__", "@@");
    }
}
