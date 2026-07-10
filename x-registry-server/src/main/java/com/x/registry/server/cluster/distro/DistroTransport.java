package com.x.registry.server.cluster.distro;

import java.util.List;

public interface DistroTransport {

    boolean syncData(String address, int port, DistroData data);

    List<DistroData> requestFullSync(String address, int port);

    String getChecksum(String address, int port);
}
