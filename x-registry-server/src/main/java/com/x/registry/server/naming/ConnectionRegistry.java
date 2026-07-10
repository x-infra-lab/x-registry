package com.x.registry.server.naming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Map<String, Set<String>> connectionInstances = new ConcurrentHashMap<>();

    public void registerConnection(String connectionId) {
        connectionInstances.computeIfAbsent(connectionId, k -> ConcurrentHashMap.newKeySet());
    }

    public void bindInstance(String connectionId, String instanceKey) {
        Set<String> keys = connectionInstances.get(connectionId);
        if (keys != null) {
            keys.add(instanceKey);
        }
    }

    public Set<String> removeConnection(String connectionId) {
        Set<String> keys = connectionInstances.remove(connectionId);
        return keys != null ? keys : Collections.emptySet();
    }

    public int getActiveConnectionCount() {
        return connectionInstances.size();
    }
}
