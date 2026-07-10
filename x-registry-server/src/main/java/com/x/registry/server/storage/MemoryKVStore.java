package com.x.registry.server.storage;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MemoryKVStore implements KVStore {

    private final ConcurrentHashMap<String, byte[]> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> versions = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0);

    @Override
    public void put(String key, byte[] value) {
        data.put(key, value);
        versions.put(key, versionCounter.incrementAndGet());
    }

    @Override
    public byte[] get(String key) {
        return data.get(key);
    }

    @Override
    public void delete(String key) {
        data.remove(key);
        versions.remove(key);
    }

    @Override
    public List<Map.Entry<String, byte[]>> scan(String prefix) {
        return data.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> (Map.Entry<String, byte[]>) new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public List<Map.Entry<String, byte[]>> scan(String prefix, long afterVersion) {
        return data.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .filter(e -> {
                    Long v = versions.get(e.getKey());
                    return v != null && v > afterVersion;
                })
                .map(e -> (Map.Entry<String, byte[]>) new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public long currentVersion() {
        return versionCounter.get();
    }

    @Override
    public void close() {
        data.clear();
        versions.clear();
    }
}
