package com.x.registry.server.grpc;

import com.x.registry.api.model.Instance;
import com.x.registry.server.naming.ConnectionRegistry;
import com.x.registry.server.naming.ServiceManager;
import com.x.registry.server.storage.InstanceStore;
import io.grpc.Attributes;
import io.grpc.ServerTransportFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ConnectionTracker extends ServerTransportFilter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTracker.class);

    private final AtomicLong connectionIdGen = new AtomicLong(0);
    private final ConnectionRegistry connectionRegistry;
    private final InstanceStore instanceStore;
    private final ServiceManager serviceManager;
    private final Semaphore maxConnectionPermits;

    public ConnectionTracker(ConnectionRegistry connectionRegistry,
                             InstanceStore instanceStore,
                             ServiceManager serviceManager,
                             @Value("${x-registry.session.max-connections:50000}") int maxConnections) {
        this.connectionRegistry = connectionRegistry;
        this.instanceStore = instanceStore;
        this.serviceManager = serviceManager;
        this.maxConnectionPermits = new Semaphore(maxConnections);
    }

    @Override
    public Attributes transportReady(Attributes transportAttrs) {
        if (!maxConnectionPermits.tryAcquire()) {
            log.warn("Connection rejected: max connection limit reached ({} active)",
                    connectionRegistry.getActiveConnectionCount());
            return transportAttrs;
        }

        String connectionId = "conn-" + connectionIdGen.incrementAndGet();
        connectionRegistry.registerConnection(connectionId);
        log.debug("Transport connected: {} (active: {})", connectionId, connectionRegistry.getActiveConnectionCount());
        return transportAttrs.toBuilder()
                .set(ConnectionInterceptor.TRANSPORT_CONNECTION_ID, connectionId)
                .build();
    }

    @Override
    public void transportTerminated(Attributes transportAttrs) {
        String connectionId = transportAttrs.get(ConnectionInterceptor.TRANSPORT_CONNECTION_ID);
        if (connectionId == null) {
            return;
        }

        maxConnectionPermits.release();

        Set<String> instanceKeys = connectionRegistry.removeConnection(connectionId);
        if (!instanceKeys.isEmpty()) {
            log.info("Transport {} terminated, removing {} ephemeral instances", connectionId, instanceKeys.size());
            for (String instanceKey : instanceKeys) {
                Instance instance = instanceStore.getInstanceByKey(instanceKey);
                if (instance != null && instance.isEphemeral()) {
                    serviceManager.removeExpiredInstance(instanceKey, instance);
                }
            }
        }
    }
}
