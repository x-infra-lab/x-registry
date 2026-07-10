package com.x.registry.server.boot;

import com.x.registry.server.storage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Value("${x-registry.storage.type:memory}")
    private String storageType;

    @Value("${x-registry.storage.rocksdb.path:./data/rocksdb}")
    private String rocksdbPath;

    @Value("${x-registry.storage.max-instance-count:100000}")
    private int maxInstanceCount;

    @Bean
    public KVStore kvStore() {
        if ("rocksdb".equalsIgnoreCase(storageType)) {
            log.info("Using RocksDB storage at {}", rocksdbPath);
            return new RocksDBKVStore(rocksdbPath);
        }
        log.info("Using in-memory storage");
        return new MemoryKVStore();
    }

    @Bean
    public InstanceStore instanceStore() {
        InstanceStore store = new InstanceStore();
        store.setMaxInstanceCount(maxInstanceCount);
        return store;
    }

    @Bean
    public ConfigStore configStore() {
        return new ConfigStore();
    }
}
