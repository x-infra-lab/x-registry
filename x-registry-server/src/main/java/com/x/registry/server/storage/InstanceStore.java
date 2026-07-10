package com.x.registry.server.storage;

import com.x.registry.api.model.Instance;
import com.x.registry.api.model.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InstanceStore {

    public static final int DEFAULT_MAX_INSTANCE_COUNT = 100_000;

    private final Map<String, Service> services = new ConcurrentHashMap<>();
    private final Map<String, Instance> instances = new ConcurrentHashMap<>();
    private volatile int maxInstanceCount = DEFAULT_MAX_INSTANCE_COUNT;

    public void setMaxInstanceCount(int maxInstanceCount) {
        this.maxInstanceCount = maxInstanceCount;
    }

    public int getMaxInstanceCount() {
        return maxInstanceCount;
    }

    public void register(String namespace, String group, String serviceName, Instance instance) {
        String serviceKey = buildServiceKey(namespace, group, serviceName);
        String instanceKey = buildInstanceKey(serviceKey, instance);

        if (!instances.containsKey(instanceKey) && instances.size() >= maxInstanceCount) {
            throw new IllegalStateException("Instance capacity limit reached: " + maxInstanceCount);
        }

        instance.setServiceName(serviceName);
        instance.setLastHeartbeat(System.currentTimeMillis());
        if (instance.getRegisterTime() == 0) {
            instance.setRegisterTime(System.currentTimeMillis());
        }

        services.computeIfAbsent(serviceKey, k -> new Service(namespace, group, serviceName));

        instances.put(instanceKey, instance);

        Service service = services.get(serviceKey);
        service.setLastModified(System.currentTimeMillis());
    }

    public void deregister(String namespace, String group, String serviceName, Instance instance) {
        String serviceKey = buildServiceKey(namespace, group, serviceName);
        String instanceKey = buildInstanceKey(serviceKey, instance);
        instances.remove(instanceKey);

        Service service = services.get(serviceKey);
        if (service != null) {
            service.setLastModified(System.currentTimeMillis());
        }
    }

    public void removeInstance(String instanceKey) {
        instances.remove(instanceKey);
    }

    public Instance getInstanceByKey(String instanceKey) {
        return instances.get(instanceKey);
    }

    public List<Instance> getInstances(String namespace, String group, String serviceName, boolean healthyOnly) {
        String serviceKey = buildServiceKey(namespace, group, serviceName);
        String prefix = serviceKey + "@@";

        List<Instance> result = new ArrayList<>();
        for (Map.Entry<String, Instance> entry : instances.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                Instance inst = entry.getValue();
                if (!healthyOnly || (inst.isHealthy() && inst.isEnabled())) {
                    result.add(inst);
                }
            }
        }
        return result;
    }

    public Instance getInstance(String namespace, String group, String serviceName, String ip, int port, String clusterName) {
        String serviceKey = buildServiceKey(namespace, group, serviceName);
        Instance lookup = new Instance();
        lookup.setIp(ip);
        lookup.setPort(port);
        lookup.setClusterName(clusterName);
        lookup.setServiceName(serviceName);
        String instanceKey = buildInstanceKey(serviceKey, lookup);
        return instances.get(instanceKey);
    }

    public Service getService(String namespace, String group, String serviceName) {
        return services.get(buildServiceKey(namespace, group, serviceName));
    }

    public Map<String, Instance> getAllEphemeralInstances() {
        return instances.entrySet().stream()
                .filter(e -> e.getValue().isEphemeral())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<String, Instance> getAllPersistentInstances() {
        return instances.entrySet().stream()
                .filter(e -> !e.getValue().isEphemeral())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<String> getAllServiceKeys() {
        return Collections.list(Collections.enumeration(services.keySet()));
    }

    private String buildServiceKey(String namespace, String group, String serviceName) {
        return namespace + "@@" + group + "@@" + serviceName;
    }

    private String buildInstanceKey(String serviceKey, Instance instance) {
        return serviceKey + "@@" + instance.getIp() + ":" + instance.getPort() + "#" + instance.getClusterName();
    }
}
