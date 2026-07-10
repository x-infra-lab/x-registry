package com.x.registry.client.event;

import com.x.registry.api.model.Instance;

import java.util.List;

public record InstancesChangeEvent(String namespace, String group, String serviceName, List<Instance> instances) {
}
