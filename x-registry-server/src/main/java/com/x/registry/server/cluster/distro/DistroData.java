package com.x.registry.server.cluster.distro;

import com.x.registry.api.model.Instance;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class DistroData implements Serializable {

    public enum Action {
        REGISTER, DEREGISTER, FULL_SYNC
    }

    private String namespace;
    private String group;
    private String serviceName;
    private Action action;
    private List<Instance> instances = new ArrayList<>();
    private String sourceNodeId;
    private long timestamp;

    public DistroData() {
        this.timestamp = System.currentTimeMillis();
    }

    public static DistroData register(String namespace, String group, String serviceName,
                                       List<Instance> instances, String sourceNodeId) {
        DistroData data = new DistroData();
        data.namespace = namespace;
        data.group = group;
        data.serviceName = serviceName;
        data.action = Action.REGISTER;
        data.instances = instances;
        data.sourceNodeId = sourceNodeId;
        return data;
    }

    public static DistroData deregister(String namespace, String group, String serviceName,
                                         List<Instance> instances, String sourceNodeId) {
        DistroData data = new DistroData();
        data.namespace = namespace;
        data.group = group;
        data.serviceName = serviceName;
        data.action = Action.DEREGISTER;
        data.instances = instances;
        data.sourceNodeId = sourceNodeId;
        return data;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public List<Instance> getInstances() {
        return instances;
    }

    public void setInstances(List<Instance> instances) {
        this.instances = instances;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
