package com.x.registry.server.audit;

public class AuditEvent {

    public enum Action {
        CONFIG_PUBLISH, CONFIG_DELETE, CONFIG_ROLLBACK,
        CONFIG_GRAY_PUBLISH, CONFIG_GRAY_PROMOTE,
        INSTANCE_REGISTER, INSTANCE_DEREGISTER,
        AUTH_SUCCESS, AUTH_FAILURE
    }

    private final long timestamp;
    private final Action action;
    private final String namespace;
    private final String resource;
    private final String operator;
    private final String detail;

    public AuditEvent(Action action, String namespace, String resource, String operator, String detail) {
        this.timestamp = System.currentTimeMillis();
        this.action = action;
        this.namespace = namespace;
        this.resource = resource;
        this.operator = operator;
        this.detail = detail;
    }

    public long getTimestamp() { return timestamp; }
    public Action getAction() { return action; }
    public String getNamespace() { return namespace; }
    public String getResource() { return resource; }
    public String getOperator() { return operator; }
    public String getDetail() { return detail; }

    @Override
    public String toString() {
        return String.format("[%d] %s ns=%s resource=%s operator=%s detail=%s",
                timestamp, action, namespace, resource, operator, detail);
    }
}
