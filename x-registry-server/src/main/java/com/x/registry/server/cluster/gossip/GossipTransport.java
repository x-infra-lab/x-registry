package com.x.registry.server.cluster.gossip;

/**
 * Transport layer abstraction for gossip protocol messages.
 * Implementations can use UDP, TCP, or gRPC.
 */
public interface GossipTransport {

    void start(int port, GossipMessageHandler handler);

    void stop();

    GossipMessage sendPing(String address, int port, GossipMessage ping);

    GossipMessage sendPingWithTimeout(String address, int port, GossipMessage ping, long timeoutMs);

    GossipMessage sendIndirectPing(String proberAddress, int proberPort,
                                   String targetAddress, int targetPort, GossipMessage ping);

    interface GossipMessageHandler {
        GossipMessage onPing(GossipMessage ping);
        GossipMessage onIndirectPing(String targetAddress, int targetPort, GossipMessage ping);
    }
}
