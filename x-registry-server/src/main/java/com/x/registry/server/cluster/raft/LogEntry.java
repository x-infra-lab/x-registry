package com.x.registry.server.cluster.raft;

import java.io.Serializable;

public class LogEntry implements Serializable {

    public enum Type {
        CONFIG_PUBLISH,
        CONFIG_DELETE,
        INSTANCE_REGISTER_PERSISTENT,
        INSTANCE_DEREGISTER_PERSISTENT
    }

    private long index;
    private long term;
    private Type type;
    private byte[] data;
    private long timestamp;

    public LogEntry() {
        this.timestamp = System.currentTimeMillis();
    }

    public LogEntry(long index, long term, Type type, byte[] data) {
        this.index = index;
        this.term = term;
        this.type = type;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public long getIndex() {
        return index;
    }

    public void setIndex(long index) {
        this.index = index;
    }

    public long getTerm() {
        return term;
    }

    public void setTerm(long term) {
        this.term = term;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
