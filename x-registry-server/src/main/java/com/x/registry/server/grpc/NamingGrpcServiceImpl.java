package com.x.registry.server.grpc;

import com.x.registry.api.grpc.*;
import com.x.registry.api.model.Instance;
import com.x.registry.server.naming.ConnectionRegistry;
import com.x.registry.server.naming.ServiceManager;
import com.x.registry.server.naming.SubscriberManager;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
public class NamingGrpcServiceImpl extends NamingGrpcServiceGrpc.NamingGrpcServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(NamingGrpcServiceImpl.class);

    private final ServiceManager serviceManager;
    private final SubscriberManager subscriberManager;
    private final ConnectionRegistry connectionRegistry;

    public NamingGrpcServiceImpl(ServiceManager serviceManager, SubscriberManager subscriberManager,
                                 ConnectionRegistry connectionRegistry) {
        this.serviceManager = serviceManager;
        this.subscriberManager = subscriberManager;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public void registerInstance(RegisterInstanceRequest request, StreamObserver<RegisterInstanceResponse> responseObserver) {
        try {
            Instance instance = fromProto(request.getInstance());
            serviceManager.registerInstance(request.getNamespace(), request.getServiceName(), request.getGroup(), instance);

            if (instance.isEphemeral()) {
                String connectionId = ConnectionInterceptor.CONNECTION_ID_KEY.get();
                if (connectionId != null) {
                    String instanceKey = request.getNamespace() + "@@" + request.getGroup() + "@@" + request.getServiceName()
                            + "@@" + instance.getIp() + ":" + instance.getPort() + "#" + instance.getClusterName();
                    connectionRegistry.bindInstance(connectionId, instanceKey);
                }
            }

            responseObserver.onNext(RegisterInstanceResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(RegisterInstanceResponse.newBuilder()
                    .setSuccess(false).setMessage(e.getMessage()).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void deregisterInstance(DeregisterInstanceRequest request, StreamObserver<DeregisterInstanceResponse> responseObserver) {
        try {
            Instance instance = fromProto(request.getInstance());
            serviceManager.deregisterInstance(request.getNamespace(), request.getServiceName(), request.getGroup(), instance);
            responseObserver.onNext(DeregisterInstanceResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(DeregisterInstanceResponse.newBuilder()
                    .setSuccess(false).setMessage(e.getMessage()).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void queryInstances(QueryInstancesRequest request, StreamObserver<QueryInstancesResponse> responseObserver) {
        List<Instance> instances = serviceManager.getInstances(
                request.getNamespace(), request.getServiceName(), request.getGroup(), request.getHealthyOnly());

        QueryInstancesResponse.Builder builder = QueryInstancesResponse.newBuilder()
                .setServiceName(request.getServiceName());
        for (Instance inst : instances) {
            builder.addInstances(toProto(inst));
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        boolean success = serviceManager.processHeartbeat(
                request.getNamespace(), request.getServiceName(), request.getGroup(),
                request.getIp(), request.getPort(), request.getClusterName());
        responseObserver.onNext(HeartbeatResponse.newBuilder()
                .setSuccess(success).setNextIntervalMs(5000).build());
        responseObserver.onCompleted();
    }

    @Override
    public void batchHeartbeat(BatchHeartbeatRequest request, StreamObserver<BatchHeartbeatResponse> responseObserver) {
        int successCount = 0;
        for (HeartbeatInstance inst : request.getInstancesList()) {
            boolean ok = serviceManager.processHeartbeat(
                    inst.getNamespace(), inst.getServiceName(), inst.getGroup(),
                    inst.getIp(), inst.getPort(),
                    inst.getClusterName().isEmpty() ? "DEFAULT" : inst.getClusterName());
            if (ok) successCount++;
        }
        responseObserver.onNext(BatchHeartbeatResponse.newBuilder()
                .setSuccess(true)
                .setSuccessCount(successCount)
                .setNextIntervalMs(5000)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<SubscribeRequest> subscribe(StreamObserver<ServiceChangeEventProto> responseObserver) {
        return new StreamObserver<>() {
            private Consumer<List<Instance>> listener;
            private String namespace;
            private String group;
            private String serviceName;

            @Override
            public void onNext(SubscribeRequest request) {
                this.namespace = request.getNamespace();
                this.group = request.getGroup();
                this.serviceName = request.getServiceName();

                this.listener = instances -> {
                    ServiceChangeEventProto.Builder event = ServiceChangeEventProto.newBuilder()
                            .setNamespace(namespace)
                            .setServiceName(serviceName)
                            .setChangeType(ChangeType.MODIFIED);
                    for (Instance inst : instances) {
                        event.addInstances(toProto(inst));
                    }
                    try {
                        responseObserver.onNext(event.build());
                    } catch (Exception e) {
                        log.warn("Failed to push to subscriber", e);
                    }
                };

                subscriberManager.subscribe(namespace, group, serviceName, listener);

                List<Instance> current = serviceManager.getInstances(namespace, serviceName, group, false);
                ServiceChangeEventProto.Builder initial = ServiceChangeEventProto.newBuilder()
                        .setNamespace(namespace)
                        .setServiceName(serviceName)
                        .setChangeType(ChangeType.MODIFIED);
                for (Instance inst : current) {
                    initial.addInstances(toProto(inst));
                }
                responseObserver.onNext(initial.build());
            }

            @Override
            public void onError(Throwable t) {
                cleanup();
            }

            @Override
            public void onCompleted() {
                cleanup();
                responseObserver.onCompleted();
            }

            private void cleanup() {
                if (listener != null && serviceName != null) {
                    subscriberManager.unsubscribe(namespace, group, serviceName, listener);
                }
            }
        };
    }

    private Instance fromProto(InstanceProto proto) {
        Instance instance = new Instance();
        instance.setIp(proto.getIp());
        instance.setPort(proto.getPort());
        instance.setWeight(proto.getWeight() > 0 ? proto.getWeight() : 1.0);
        instance.setHealthy(proto.getHealthy());
        instance.setEnabled(proto.getEnabled());
        instance.setEphemeral(proto.getEphemeral());
        instance.setClusterName(proto.getClusterName().isEmpty() ? "DEFAULT" : proto.getClusterName());
        instance.setMetadata(proto.getMetadataMap());
        return instance;
    }

    static InstanceProto toProto(Instance instance) {
        InstanceProto.Builder builder = InstanceProto.newBuilder()
                .setInstanceId(instance.getInstanceId())
                .setServiceName(instance.getServiceName() != null ? instance.getServiceName() : "")
                .setClusterName(instance.getClusterName() != null ? instance.getClusterName() : "DEFAULT")
                .setIp(instance.getIp())
                .setPort(instance.getPort())
                .setWeight(instance.getWeight())
                .setHealthy(instance.isHealthy())
                .setEnabled(instance.isEnabled())
                .setEphemeral(instance.isEphemeral())
                .setLastHeartbeat(instance.getLastHeartbeat())
                .setRegisterTime(instance.getRegisterTime());
        if (instance.getMetadata() != null) {
            builder.putAllMetadata(instance.getMetadata());
        }
        return builder.build();
    }
}
