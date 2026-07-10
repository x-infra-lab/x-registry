package com.x.registry.server.naming;

import com.x.registry.api.model.Instance;
import com.x.registry.api.exception.XRegistryException;
import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.cluster.distro.DistroData;
import com.x.registry.server.cluster.distro.DistroProtocol;
import com.x.registry.server.storage.InstanceStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class ServiceManager {

    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);

    private final InstanceStore instanceStore;
    private final SubscriberManager subscriberManager;
    private final PushAggregator pushAggregator;
    private final ClusterManager clusterManager;
    private final Counter registerCounter;
    private final Counter deregisterCounter;
    private final Counter heartbeatCounter;

    public ServiceManager(InstanceStore instanceStore, SubscriberManager subscriberManager,
                          PushAggregator pushAggregator, MeterRegistry registry,
                          ClusterManager clusterManager) {
        this.instanceStore = instanceStore;
        this.subscriberManager = subscriberManager;
        this.pushAggregator = pushAggregator;
        this.clusterManager = clusterManager;
        this.registerCounter = Counter.builder("x_registry_instance_register_total")
                .description("Total instance registrations")
                .register(registry);
        this.deregisterCounter = Counter.builder("x_registry_instance_deregister_total")
                .description("Total instance deregistrations")
                .register(registry);
        this.heartbeatCounter = Counter.builder("x_registry_heartbeat_total")
                .description("Total heartbeats processed")
                .register(registry);
    }

    public void registerInstance(String namespace, String serviceName, String group, Instance instance) {
        validateInstance(instance);

        if (clusterManager.isClustered() && !instance.isEphemeral()) {
            // Persistent instance: route through Raft for CP consistency
            boolean success = clusterManager.proposeInstanceRegister(namespace, group, serviceName, instance)
                    .join();
            if (!success) {
                throw XRegistryException.serverError("Failed to register persistent instance via Raft (not leader or commit failed)");
            }
        } else {
            instanceStore.register(namespace, group, serviceName, instance);
        }

        registerCounter.increment();
        log.info("Registered instance: {}:{} for service {}", instance.getIp(), instance.getPort(), serviceName);
        notifySubscribers(namespace, group, serviceName);

        // Ephemeral instance in cluster mode: async Distro sync to peers
        if (clusterManager.isClustered() && instance.isEphemeral()) {
            DistroProtocol distro = clusterManager.getDistroProtocol();
            if (distro != null) {
                DistroData syncData = DistroData.register(namespace, group, serviceName,
                        Collections.singletonList(instance),
                        clusterManager.getMemberManager().getSelf().getId());
                distro.syncToOthers(syncData);
            }
        }
    }

    public void deregisterInstance(String namespace, String serviceName, String group, Instance instance) {
        if (clusterManager.isClustered() && !instance.isEphemeral()) {
            boolean success = clusterManager.proposeInstanceDeregister(namespace, group, serviceName, instance)
                    .join();
            if (!success) {
                throw XRegistryException.serverError("Failed to deregister persistent instance via Raft");
            }
        } else {
            instanceStore.deregister(namespace, group, serviceName, instance);
        }

        deregisterCounter.increment();
        log.info("Deregistered instance: {}:{} from service {}", instance.getIp(), instance.getPort(), serviceName);
        notifySubscribers(namespace, group, serviceName);

        if (clusterManager.isClustered() && instance.isEphemeral()) {
            DistroProtocol distro = clusterManager.getDistroProtocol();
            if (distro != null) {
                DistroData syncData = DistroData.deregister(namespace, group, serviceName,
                        Collections.singletonList(instance),
                        clusterManager.getMemberManager().getSelf().getId());
                distro.syncToOthers(syncData);
            }
        }
    }

    public List<Instance> getInstances(String namespace, String serviceName, String group, boolean healthyOnly) {
        return instanceStore.getInstances(namespace, group, serviceName, healthyOnly);
    }

    public boolean processHeartbeat(String namespace, String serviceName, String group,
                                    String ip, int port, String clusterName) {
        heartbeatCounter.increment();
        Instance instance = instanceStore.getInstance(namespace, group, serviceName, ip, port, clusterName);
        if (instance == null) {
            return false;
        }
        instance.setLastHeartbeat(System.currentTimeMillis());
        if (!instance.isHealthy()) {
            instance.setHealthy(true);
            log.info("Instance {}:{} marked healthy via heartbeat", ip, port);
            notifySubscribers(namespace, group, serviceName);
        }
        return true;
    }

    public void markUnhealthy(String namespace, String group, String serviceName, Instance instance) {
        if (instance.isHealthy()) {
            instance.setHealthy(false);
            log.warn("Instance {}:{} marked unhealthy for service {}", instance.getIp(), instance.getPort(), serviceName);
            notifySubscribers(namespace, group, serviceName);
        }
    }

    public void markHealthy(String namespace, String group, String serviceName, Instance instance) {
        if (!instance.isHealthy()) {
            instance.setHealthy(true);
            log.info("Instance {}:{} marked healthy for service {}", instance.getIp(), instance.getPort(), serviceName);
            notifySubscribers(namespace, group, serviceName);
        }
    }

    public void removeExpiredInstance(String instanceKey, Instance instance) {
        instanceStore.removeInstance(instanceKey);
        log.warn("Removed expired instance: {}:{} from service {}",
                instance.getIp(), instance.getPort(), instance.getServiceName());
        String namespace = extractNamespace(instanceKey);
        String group = extractGroup(instanceKey);
        String serviceName = instance.getServiceName();
        notifySubscribers(namespace, group, serviceName);
    }

    private void notifySubscribers(String namespace, String group, String serviceName) {
        pushAggregator.markDirty(namespace, group, serviceName);
    }

    private void validateInstance(Instance instance) {
        if (instance.getIp() == null || instance.getIp().isEmpty()) {
            throw XRegistryException.invalidParam("instance.ip is required");
        }
        if (instance.getPort() <= 0 || instance.getPort() > 65535) {
            throw XRegistryException.invalidParam("instance.port must be between 1 and 65535");
        }
    }

    private String extractNamespace(String instanceKey) {
        return instanceKey.split("@@")[0];
    }

    private String extractGroup(String instanceKey) {
        return instanceKey.split("@@")[1];
    }
}
