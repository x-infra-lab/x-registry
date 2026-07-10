package com.x.registry.spring;

import com.x.registry.api.model.Instance;
import com.x.registry.api.naming.NamingService;

import java.util.List;
import java.util.function.Consumer;

public class XRegistryDiscoveryClient {

    private final NamingService namingService;
    private final XRegistryProperties properties;

    public XRegistryDiscoveryClient(NamingService namingService, XRegistryProperties properties) {
        this.namingService = namingService;
        this.properties = properties;
    }

    public List<Instance> getInstances(String serviceName) {
        return namingService.getInstances(properties.getNamespace(), serviceName, properties.getGroup(), true);
    }

    public List<Instance> getInstances(String serviceName, boolean healthyOnly) {
        return namingService.getInstances(properties.getNamespace(), serviceName, properties.getGroup(), healthyOnly);
    }

    public List<Instance> getInstances(String namespace, String serviceName, String group, boolean healthyOnly) {
        return namingService.getInstances(namespace, serviceName, group, healthyOnly);
    }

    public void subscribe(String serviceName, Consumer<List<Instance>> listener) {
        namingService.subscribe(properties.getNamespace(), serviceName, properties.getGroup(), listener);
    }

    public void unsubscribe(String serviceName, Consumer<List<Instance>> listener) {
        namingService.unsubscribe(properties.getNamespace(), serviceName, properties.getGroup(), listener);
    }
}
