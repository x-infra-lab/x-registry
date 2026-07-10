package com.x.registry.server.storage;

import java.util.List;
import java.util.Map;

public interface KVStore extends AutoCloseable {

    void put(String key, byte[] value);

    byte[] get(String key);

    void delete(String key);

    List<Map.Entry<String, byte[]>> scan(String prefix);

    List<Map.Entry<String, byte[]>> scan(String prefix, long afterVersion);

    long currentVersion();

    @Override
    void close();
}
