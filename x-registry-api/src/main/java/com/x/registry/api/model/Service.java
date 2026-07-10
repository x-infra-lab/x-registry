package com.x.registry.api.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Service implements Serializable {

    private String namespace = "public";
    private String group = "DEFAULT_GROUP";
    private String name;
    private List<Instance> instances = new ArrayList<>();
    private long lastModified;

    public Service() {
    }

    public Service(String namespace, String group, String name) {
        this.namespace = namespace;
        this.group = group;
        this.name = name;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Instance> getInstances() {
        return instances;
    }

    public void setInstances(List<Instance> instances) {
        this.instances = instances;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public String getKey() {
        return namespace + "@@" + group + "@@" + name;
    }
}
