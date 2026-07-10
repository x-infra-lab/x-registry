package com.x.registry.client.naming;

import com.x.registry.api.grpc.*;
import com.x.registry.api.model.Instance;
import com.x.registry.api.naming.NamingService;
import com.x.registry.client.XRegistryClientConfig;
import com.x.registry.client.cache.LocalCacheManager;
import com.x.registry.client.connection.ConnectionManager;
import com.x.registry.client.event.EventBus;
import com.x.registry.client.event.InstancesChangeEvent;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class NamingServiceImpl implements NamingService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NamingServiceImpl.class);

    private final XRegistryClientConfig config;
    private final ConnectionManager connectionManager;
    private final LocalCacheManager cacheManager;
    private final EventBus eventBus;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Map<String, HeartbeatEntry> registeredInstances = new ConcurrentHashMap<>();
    private final Map<String, StreamObserver<SubscribeRequest>> subscribeStreams = new ConcurrentHashMap<>();
    private final Map<String, Consumer<List<Instance>>> subscribeListeners = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconcileScheduler;
    private final AtomicInteger consecutiveHeartbeatSuccess = new AtomicInteger(0);
    private volatile ScheduledFuture<?> heartbeatFuture;

    public NamingServiceImpl(XRegistryClientConfig config, ConnectionManager connectionManager,
                             LocalCacheManager cacheManager, EventBus eventBus) {
        this.config = config;
        this.connectionManager = connectionManager;
        this.cacheManager = cacheManager;
        this.eventBus = eventBus;
        this.heartbeatScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "heartbeat-sender");
            t.setDaemon(true);
            return t;
        });
        scheduleNextHeartbeat(config.getHeartbeatIntervalMs());
        this.reconcileScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "service-reconcile");
            t.setDaemon(true);
            return t;
        });
        this.reconcileScheduler.scheduleWithFixedDelay(this::reconcileSubscriptions, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void registerInstance(String namespace, String serviceName, String group, Instance instance) {
        NamingGrpcServiceGrpc.NamingGrpcServiceBlockingStub stub =
                NamingGrpcServiceGrpc.newBlockingStub(connectionManager.getChannel())
                .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

        InstanceProto proto = buildInstanceProto(instance);
        RegisterInstanceRequest request = RegisterInstanceRequest.newBuilder()
                .setNamespace(namespace)
                .setServiceName(serviceName)
                .setGroup(group)
                .setInstance(proto)
                .build();

        RegisterInstanceResponse response = stub.registerInstance(request);
        if (!response.getSuccess()) {
            throw new RuntimeException("Register failed: " + response.getMessage());
        }

        if (instance.isEphemeral()) {
            String key = namespace + "@@" + group + "@@" + serviceName + "@@" + instance.getIp() + ":" + instance.getPort();
            registeredInstances.put(key, new HeartbeatEntry(namespace, serviceName, group, instance));
        }
    }

    @Override
    public void deregisterInstance(String namespace, String serviceName, String group, Instance instance) {
        String key = namespace + "@@" + group + "@@" + serviceName + "@@" + instance.getIp() + ":" + instance.getPort();
        registeredInstances.remove(key);

        NamingGrpcServiceGrpc.NamingGrpcServiceBlockingStub stub =
                NamingGrpcServiceGrpc.newBlockingStub(connectionManager.getChannel())
                .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

        InstanceProto proto = buildInstanceProto(instance);
        DeregisterInstanceRequest request = DeregisterInstanceRequest.newBuilder()
                .setNamespace(namespace)
                .setServiceName(serviceName)
                .setGroup(group)
                .setInstance(proto)
                .build();

        stub.deregisterInstance(request);
    }

    @Override
    public List<Instance> getInstances(String namespace, String serviceName, String group, boolean healthyOnly) {
        try {
            NamingGrpcServiceGrpc.NamingGrpcServiceBlockingStub stub =
                    NamingGrpcServiceGrpc.newBlockingStub(connectionManager.getChannel())
                .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

            QueryInstancesRequest request = QueryInstancesRequest.newBuilder()
                    .setNamespace(namespace)
                    .setServiceName(serviceName)
                    .setGroup(group)
                    .setHealthyOnly(healthyOnly)
                    .build();

            QueryInstancesResponse response = stub.queryInstances(request);
            List<Instance> result = new ArrayList<>();
            for (InstanceProto proto : response.getInstancesList()) {
                result.add(fromProto(proto));
            }

            cacheManager.cacheInstances(namespace, group, serviceName, result);
            return result;
        } catch (Exception e) {
            log.warn("Failed to query instances from server, falling back to local cache: {}", e.getMessage());
            List<Instance> cached = cacheManager.getCachedInstances(namespace, group, serviceName);
            if (!cached.isEmpty()) {
                log.info("Returning {} cached instances for {}/{}/{}", cached.size(), namespace, group, serviceName);
                return cached;
            }
            throw new RuntimeException("Server unavailable and no cached instances for " + serviceName, e);
        }
    }

    @Override
    public void subscribe(String namespace, String serviceName, String group, Consumer<List<Instance>> listener) {
        NamingGrpcServiceGrpc.NamingGrpcServiceStub asyncStub =
                NamingGrpcServiceGrpc.newStub(connectionManager.getChannel());

        StreamObserver<SubscribeRequest> requestObserver = asyncStub.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(ServiceChangeEventProto event) {
                List<Instance> instances = new ArrayList<>();
                for (InstanceProto proto : event.getInstancesList()) {
                    instances.add(fromProto(proto));
                }
                cacheManager.cacheInstances(namespace, group, serviceName, instances);
                if (eventBus != null) {
                    eventBus.publish(new InstancesChangeEvent(namespace, group, serviceName, instances));
                }
                listener.accept(instances);
            }

            @Override
            public void onError(Throwable t) {
                log.error("Subscribe stream error for service {}", serviceName, t);
            }

            @Override
            public void onCompleted() {
                log.info("Subscribe stream completed for service {}", serviceName);
            }
        });

        requestObserver.onNext(SubscribeRequest.newBuilder()
                .setNamespace(namespace)
                .setServiceName(serviceName)
                .setGroup(group)
                .build());

        String key = namespace + "@@" + group + "@@" + serviceName;
        subscribeStreams.put(key, requestObserver);
        subscribeListeners.put(key, listener);
    }

    @Override
    public void unsubscribe(String namespace, String serviceName, String group, Consumer<List<Instance>> listener) {
        String key = namespace + "@@" + group + "@@" + serviceName;
        subscribeListeners.remove(key);
        StreamObserver<SubscribeRequest> stream = subscribeStreams.remove(key);
        if (stream != null) {
            stream.onCompleted();
        }
    }

    private void sendBatchHeartbeat() {
        if (registeredInstances.isEmpty()) {
            return;
        }

        try {
            NamingGrpcServiceGrpc.NamingGrpcServiceBlockingStub stub =
                    NamingGrpcServiceGrpc.newBlockingStub(connectionManager.getChannel())
                .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

            BatchHeartbeatRequest.Builder builder = BatchHeartbeatRequest.newBuilder();
            for (HeartbeatEntry entry : registeredInstances.values()) {
                builder.addInstances(HeartbeatInstance.newBuilder()
                        .setNamespace(entry.namespace)
                        .setServiceName(entry.serviceName)
                        .setGroup(entry.group)
                        .setIp(entry.instance.getIp())
                        .setPort(entry.instance.getPort())
                        .setClusterName(entry.instance.getClusterName() != null ? entry.instance.getClusterName() : "DEFAULT")
                        .build());
            }

            BatchHeartbeatResponse response = stub.batchHeartbeat(builder.build());
            if (!response.getSuccess()) {
                log.warn("Batch heartbeat failed");
                consecutiveHeartbeatSuccess.set(0);
            } else {
                consecutiveHeartbeatSuccess.incrementAndGet();
            }
        } catch (Exception e) {
            log.warn("Batch heartbeat error: {}", e.getMessage());
            consecutiveHeartbeatSuccess.set(0);
        }

        scheduleNextHeartbeat(computeAdaptiveInterval());
    }

    private long computeAdaptiveInterval() {
        int successes = consecutiveHeartbeatSuccess.get();
        if (successes > 10) return 15_000;
        if (successes > 5) return 10_000;
        return config.getHeartbeatIntervalMs();
    }

    private void scheduleNextHeartbeat(long delayMs) {
        heartbeatFuture = heartbeatScheduler.schedule(this::sendBatchHeartbeat, delayMs, TimeUnit.MILLISECONDS);
    }

    private record HeartbeatEntry(String namespace, String serviceName, String group, Instance instance) {}

    private void reconcileSubscriptions() {
        for (Map.Entry<String, Consumer<List<Instance>>> entry : subscribeListeners.entrySet()) {
            String key = entry.getKey();
            Consumer<List<Instance>> listener = entry.getValue();
            String[] parts = key.split("@@");
            if (parts.length != 3) continue;

            String namespace = parts[0];
            String group = parts[1];
            String serviceName = parts[2];

            try {
                List<Instance> instances = getInstances(namespace, serviceName, group, false);
                if (!instances.isEmpty()) {
                    listener.accept(instances);
                }
            } catch (Exception e) {
                log.debug("Reconcile pull failed for {}, will retry next cycle", serviceName);
            }
        }
    }

    @Override
    public void close() {
        registeredInstances.clear();
        heartbeatScheduler.shutdown();
        reconcileScheduler.shutdown();
        subscribeStreams.values().forEach(StreamObserver::onCompleted);
        subscribeStreams.clear();
        subscribeListeners.clear();
    }

    private InstanceProto buildInstanceProto(Instance instance) {
        InstanceProto.Builder builder = InstanceProto.newBuilder()
                .setIp(instance.getIp())
                .setPort(instance.getPort())
                .setWeight(instance.getWeight())
                .setHealthy(instance.isHealthy())
                .setEnabled(instance.isEnabled())
                .setEphemeral(instance.isEphemeral())
                .setClusterName(instance.getClusterName() != null ? instance.getClusterName() : "DEFAULT");
        if (instance.getMetadata() != null) {
            builder.putAllMetadata(instance.getMetadata());
        }
        return builder.build();
    }

    private Instance fromProto(InstanceProto proto) {
        Instance instance = new Instance();
        instance.setInstanceId(proto.getInstanceId());
        instance.setServiceName(proto.getServiceName());
        instance.setClusterName(proto.getClusterName());
        instance.setIp(proto.getIp());
        instance.setPort(proto.getPort());
        instance.setWeight(proto.getWeight());
        instance.setHealthy(proto.getHealthy());
        instance.setEnabled(proto.getEnabled());
        instance.setEphemeral(proto.getEphemeral());
        instance.setMetadata(proto.getMetadataMap());
        instance.setLastHeartbeat(proto.getLastHeartbeat());
        instance.setRegisterTime(proto.getRegisterTime());
        return instance;
    }
}
