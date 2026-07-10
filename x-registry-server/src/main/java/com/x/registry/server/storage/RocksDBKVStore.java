package com.x.registry.server.storage;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RocksDBKVStore implements KVStore {

    private static final Logger log = LoggerFactory.getLogger(RocksDBKVStore.class);

    private final RocksDB db;
    private final Options options;
    private final ConcurrentHashMap<String, Long> versions = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0);

    static {
        RocksDB.loadLibrary();
    }

    public RocksDBKVStore(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            this.options = new Options()
                    .setCreateIfMissing(true)
                    .setWriteBufferSize(64 * 1024 * 1024)
                    .setMaxWriteBufferNumber(3)
                    .setTargetFileSizeBase(64 * 1024 * 1024);

            this.db = RocksDB.open(options, path);
            log.info("RocksDB opened at {}", path);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open RocksDB at " + path, e);
        }
    }

    @Override
    public void put(String key, byte[] value) {
        try {
            db.put(key.getBytes(StandardCharsets.UTF_8), value);
            versions.put(key, versionCounter.incrementAndGet());
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB put failed for key: " + key, e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return db.get(key.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB get failed for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            db.delete(key.getBytes(StandardCharsets.UTF_8));
            versions.remove(key);
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB delete failed for key: " + key, e);
        }
    }

    @Override
    public List<Map.Entry<String, byte[]>> scan(String prefix) {
        List<Map.Entry<String, byte[]>> result = new ArrayList<>();
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);

        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(prefixBytes);
            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                if (!key.startsWith(prefix)) {
                    break;
                }
                result.add(new AbstractMap.SimpleImmutableEntry<>(key, iterator.value()));
                iterator.next();
            }
        }
        return result;
    }

    @Override
    public List<Map.Entry<String, byte[]>> scan(String prefix, long afterVersion) {
        List<Map.Entry<String, byte[]>> result = new ArrayList<>();
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);

        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(prefixBytes);
            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                if (!key.startsWith(prefix)) {
                    break;
                }
                Long v = versions.get(key);
                if (v != null && v > afterVersion) {
                    result.add(new AbstractMap.SimpleImmutableEntry<>(key, iterator.value()));
                }
                iterator.next();
            }
        }
        return result;
    }

    @Override
    public long currentVersion() {
        return versionCounter.get();
    }

    @Override
    public void close() {
        db.close();
        options.close();
        log.info("RocksDB closed");
    }
}
