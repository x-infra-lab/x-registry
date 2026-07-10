package com.x.registry.server.grpc;

import com.x.registry.api.grpc.*;
import com.x.registry.api.model.Instance;
import com.x.registry.server.naming.ConnectionRegistry;
import com.x.registry.server.naming.ServiceManager;
import com.x.registry.server.naming.SubscriberManager;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NamingGrpcServiceTest {

    @Mock
    private ServiceManager serviceManager;

    @Mock
    private SubscriberManager subscriberManager;

    @Mock
    private ConnectionRegistry connectionRegistry;

    private NamingGrpcServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NamingGrpcServiceImpl(serviceManager, subscriberManager, connectionRegistry);
    }

    // -------- helper --------

    private static class TestStreamObserver<T> implements StreamObserver<T> {
        T value;
        Throwable error;
        boolean completed;

        @Override
        public void onNext(T v) {
            this.value = v;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }

    private static InstanceProto buildInstanceProto(String ip, int port) {
        return InstanceProto.newBuilder()
                .setIp(ip)
                .setPort(port)
                .setWeight(1.0)
                .setHealthy(true)
                .setEnabled(true)
                .setEphemeral(true)
                .setClusterName("DEFAULT")
                .build();
    }

    private static Instance buildInstance(String ip, int port, String serviceName) {
        Instance inst = new Instance();
        inst.setIp(ip);
        inst.setPort(port);
        inst.setWeight(1.0);
        inst.setHealthy(true);
        inst.setEnabled(true);
        inst.setEphemeral(true);
        inst.setClusterName("DEFAULT");
        inst.setServiceName(serviceName);
        inst.setInstanceId(ip + ":" + port + "#DEFAULT#" + serviceName);
        return inst;
    }

    // -------- registerInstance tests --------

    @Test
    void registerInstance_success() {
        RegisterInstanceRequest request = RegisterInstanceRequest.newBuilder()
                .setNamespace("public")
                .setServiceName("order-service")
                .setGroup("DEFAULT_GROUP")
                .setInstance(buildInstanceProto("10.0.0.1", 8080))
                .build();

        TestStreamObserver<RegisterInstanceResponse> observer = new TestStreamObserver<>();
        service.registerInstance(request, observer);

        verify(serviceManager).registerInstance(eq("public"), eq("order-service"), eq("DEFAULT_GROUP"), any(Instance.class));
        assertNotNull(observer.value);
        assertTrue(observer.value.getSuccess());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    @Test
    void registerInstance_failure() {
        doThrow(new RuntimeException("Registration failed"))
                .when(serviceManager)
                .registerInstance(anyString(), anyString(), anyString(), any(Instance.class));

        RegisterInstanceRequest request = RegisterInstanceRequest.newBuilder()
                .setNamespace("public")
                .setServiceName("order-service")
                .setGroup("DEFAULT_GROUP")
                .setInstance(buildInstanceProto("10.0.0.1", 8080))
                .build();

        TestStreamObserver<RegisterInstanceResponse> observer = new TestStreamObserver<>();
        service.registerInstance(request, observer);

        assertNotNull(observer.value);
        assertFalse(observer.value.getSuccess());
        assertEquals("Registration failed", observer.value.getMessage());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    // -------- deregisterInstance tests --------

    @Test
    void deregisterInstance_success() {
        DeregisterInstanceRequest request = DeregisterInstanceRequest.newBuilder()
                .setNamespace("public")
                .setServiceName("order-service")
                .setGroup("DEFAULT_GROUP")
                .setInstance(buildInstanceProto("10.0.0.1", 8080))
                .build();

        TestStreamObserver<DeregisterInstanceResponse> observer = new TestStreamObserver<>();
        service.deregisterInstance(request, observer);

        verify(serviceManager).deregisterInstance(eq("public"), eq("order-service"), eq("DEFAULT_GROUP"), any(Instance.class));
        assertNotNull(observer.value);
        assertTrue(observer.value.getSuccess());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    // -------- queryInstances tests --------

    @Test
    void queryInstances_returnsInstances() {
        Instance inst1 = buildInstance("10.0.0.1", 8080, "order-service");
        Instance inst2 = buildInstance("10.0.0.2", 8081, "order-service");
        List<Instance> instances = Arrays.asList(inst1, inst2);

        when(serviceManager.getInstances("public", "order-service", "DEFAULT_GROUP", false))
                .thenReturn(instances);

        QueryInstancesRequest request = QueryInstancesRequest.newBuilder()
                .setNamespace("public")
                .setServiceName("order-service")
                .setGroup("DEFAULT_GROUP")
                .setHealthyOnly(false)
                .build();

        TestStreamObserver<QueryInstancesResponse> observer = new TestStreamObserver<>();
        service.queryInstances(request, observer);

        assertNotNull(observer.value);
        assertEquals(2, observer.value.getInstancesCount());
        assertEquals("order-service", observer.value.getServiceName());
        assertEquals("10.0.0.1", observer.value.getInstances(0).getIp());
        assertEquals(8080, observer.value.getInstances(0).getPort());
        assertEquals("10.0.0.2", observer.value.getInstances(1).getIp());
        assertEquals(8081, observer.value.getInstances(1).getPort());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    // -------- heartbeat tests --------

    @Test
    void heartbeat_success() {
        when(serviceManager.processHeartbeat("public", "order-service", "DEFAULT_GROUP", "10.0.0.1", 8080, "DEFAULT"))
                .thenReturn(true);

        HeartbeatRequest request = HeartbeatRequest.newBuilder()
                .setNamespace("public")
                .setServiceName("order-service")
                .setGroup("DEFAULT_GROUP")
                .setIp("10.0.0.1")
                .setPort(8080)
                .setClusterName("DEFAULT")
                .build();

        TestStreamObserver<HeartbeatResponse> observer = new TestStreamObserver<>();
        service.heartbeat(request, observer);

        assertNotNull(observer.value);
        assertTrue(observer.value.getSuccess());
        assertEquals(5000, observer.value.getNextIntervalMs());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }
}
