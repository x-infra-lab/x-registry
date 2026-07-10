package com.x.registry.api.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Instance implements Serializable {

    private String instanceId;
    private String serviceName;
    private String clusterName = "DEFAULT";
    private String ip;
    private int port;
    private double weight = 1.0;
    private boolean healthy = true;
    private boolean enabled = true;
    private boolean ephemeral = true;
    private Map<String, String> metadata = new HashMap<>();
    private long lastHeartbeat;
    private long registerTime;

    public String getInstanceId() {
        if (instanceId == null) {
            instanceId = ip + ":" + port + "#" + clusterName + "#" + serviceName;
        }
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public long getRegisterTime() {
        return registerTime;
    }

    public void setRegisterTime(long registerTime) {
        this.registerTime = registerTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Instance instance = (Instance) o;
        return port == instance.port && Objects.equals(ip, instance.ip)
                && Objects.equals(clusterName, instance.clusterName)
                && Objects.equals(serviceName, instance.serviceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip, port, clusterName, serviceName);
    }

    @Override
    public String toString() {
        return "Instance{" + ip + ":" + port + ", service=" + serviceName
                + ", cluster=" + clusterName + ", healthy=" + healthy + "}";
    }
}
