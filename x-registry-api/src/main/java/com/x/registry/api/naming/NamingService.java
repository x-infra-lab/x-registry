package com.x.registry.api.naming;

import com.x.registry.api.model.Instance;

import java.util.List;
import java.util.function.Consumer;

public interface NamingService {

    void registerInstance(String namespace, String serviceName, String group, Instance instance);

    void deregisterInstance(String namespace, String serviceName, String group, Instance instance);

    List<Instance> getInstances(String namespace, String serviceName, String group, boolean healthyOnly);

    void subscribe(String namespace, String serviceName, String group, Consumer<List<Instance>> listener);

    void unsubscribe(String namespace, String serviceName, String group, Consumer<List<Instance>> listener);
}
