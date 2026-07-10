package com.x.registry.server.config;

import java.util.Set;

public class GrayRule {

    public enum GrayType {
        IP, LABEL
    }

    private String namespace;
    private String group;
    private String dataId;
    private GrayType type;
    private Set<String> targets;
    private String grayContent;
    private int priority;

    public GrayRule() {
    }

    public GrayRule(String namespace, String group, String dataId, GrayType type,
                    Set<String> targets, String grayContent, int priority) {
        this.namespace = namespace;
        this.group = group;
        this.dataId = dataId;
        this.type = type;
        this.targets = targets;
        this.grayContent = grayContent;
        this.priority = priority;
    }

    public boolean matches(String clientIp, Set<String> clientLabels) {
        if (type == GrayType.IP) {
            return targets != null && targets.contains(clientIp);
        } else if (type == GrayType.LABEL) {
            if (targets == null || clientLabels == null) return false;
            for (String target : targets) {
                if (clientLabels.contains(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public String getDataId() { return dataId; }
    public void setDataId(String dataId) { this.dataId = dataId; }
    public GrayType getType() { return type; }
    public void setType(GrayType type) { this.type = type; }
    public Set<String> getTargets() { return targets; }
    public void setTargets(Set<String> targets) { this.targets = targets; }
    public String getGrayContent() { return grayContent; }
    public void setGrayContent(String grayContent) { this.grayContent = grayContent; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
}
