package com.x.registry.server.boot;

import com.x.registry.api.model.Instance;
import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.cluster.raft.RatisServer;
import com.x.registry.server.naming.ConnectionRegistry;
import com.x.registry.server.storage.InstanceStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    public MetricsConfig(MeterRegistry registry, InstanceStore instanceStore,
                         ConnectionRegistry connectionRegistry, ClusterManager clusterManager) {

        Gauge.builder("x_registry_grpc_connection_count", connectionRegistry,
                ConnectionRegistry::getActiveConnectionCount)
                .description("Current active gRPC connections")
                .register(registry);

        Gauge.builder("x_registry_instance_count", instanceStore, store ->
                store.getAllEphemeralInstances().size() + store.getAllPersistentInstances().size()
        ).description("Total registered instance count").register(registry);

        Gauge.builder("x_registry_healthy_instance_count", instanceStore, store -> {
            long healthy = 0;
            for (Instance inst : store.getAllEphemeralInstances().values()) {
                if (inst.isHealthy()) healthy++;
            }
            for (Instance inst : store.getAllPersistentInstances().values()) {
                if (inst.isHealthy()) healthy++;
            }
            return healthy;
        }).description("Total healthy instance count").register(registry);

        Gauge.builder("x_registry_service_count", instanceStore, store ->
                store.getAllServiceKeys().size()
        ).description("Total registered service count").register(registry);

        Gauge.builder("x_registry_raft_term", clusterManager, cm -> {
            RatisServer raft = cm.getRatisServer();
            return raft != null ? raft.getCurrentTerm() : 0;
        }).description("Current Raft term").register(registry);

        Gauge.builder("x_registry_raft_commit_index", clusterManager, cm -> {
            RatisServer raft = cm.getRatisServer();
            return raft != null ? raft.getCommitIndex() : 0;
        }).description("Current Raft commit index").register(registry);
    }
}
