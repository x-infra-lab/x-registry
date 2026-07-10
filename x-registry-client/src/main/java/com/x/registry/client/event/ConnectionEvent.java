package com.x.registry.client.event;

public record ConnectionEvent(Type type, String serverAddr) {

    public enum Type {
        CONNECTED,
        DISCONNECTED,
        RECONNECTING
    }
}
