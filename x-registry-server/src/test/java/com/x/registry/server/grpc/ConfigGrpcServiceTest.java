package com.x.registry.server.grpc;

import com.x.registry.api.grpc.*;
import com.x.registry.api.model.ConfigItem;
import com.x.registry.server.config.ConfigManager;
import com.x.registry.server.config.ConfigWatcherManager;
import com.x.registry.server.config.GrayRuleManager;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigGrpcServiceTest {

    @Mock
    private ConfigManager configManager;

    @Mock
    private ConfigWatcherManager watcherManager;

    @Mock
    private GrayRuleManager grayRuleManager;

    private ConfigGrpcServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConfigGrpcServiceImpl(configManager, watcherManager, grayRuleManager);
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

    // -------- getConfig tests --------

    @Test
    void getConfig_found_returnsConfig() {
        ConfigItem item = new ConfigItem("public", "DEFAULT_GROUP", "app.yaml");
        item.setContent("key: value");
        item.setContentType("yaml");
        item.setVersion(3);
        item.setLastModified(System.currentTimeMillis());
        item.setOperator("admin");
        item.setDescription("test config");

        when(configManager.getConfig("public", "app.yaml", "DEFAULT_GROUP")).thenReturn(item);
        // No gray rule applied — return default content
        when(grayRuleManager.resolveContent(
                eq("public"), eq("DEFAULT_GROUP"), eq("app.yaml"),
                any(), any(Set.class), eq("key: value")))
                .thenReturn("key: value");

        GetConfigRequest request = GetConfigRequest.newBuilder()
                .setNamespace("public")
                .setGroup("DEFAULT_GROUP")
                .setDataId("app.yaml")
                .build();

        TestStreamObserver<GetConfigResponse> observer = new TestStreamObserver<>();
        service.getConfig(request, observer);

        assertNotNull(observer.value);
        assertTrue(observer.value.getFound());
        assertEquals("key: value", observer.value.getConfig().getContent());
        assertEquals("yaml", observer.value.getConfig().getContentType());
        assertEquals(3, observer.value.getConfig().getVersion());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    @Test
    void getConfig_notFound_returnsFalse() {
        when(configManager.getConfig("public", "missing.yaml", "DEFAULT_GROUP")).thenReturn(null);

        GetConfigRequest request = GetConfigRequest.newBuilder()
                .setNamespace("public")
                .setGroup("DEFAULT_GROUP")
                .setDataId("missing.yaml")
                .build();

        TestStreamObserver<GetConfigResponse> observer = new TestStreamObserver<>();
        service.getConfig(request, observer);

        assertNotNull(observer.value);
        assertFalse(observer.value.getFound());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    // -------- publishConfig tests --------

    @Test
    void publishConfig_success() {
        ConfigItem published = new ConfigItem("public", "DEFAULT_GROUP", "app.yaml");
        published.setContent("key: newValue");
        published.setVersion(1);
        published.setMd5("abc123");

        when(configManager.publishConfig(
                eq("public"), eq("app.yaml"), eq("DEFAULT_GROUP"),
                eq("key: newValue"), eq("yaml"), eq("admin"), eq("initial")))
                .thenReturn(published);

        PublishConfigRequest request = PublishConfigRequest.newBuilder()
                .setNamespace("public")
                .setGroup("DEFAULT_GROUP")
                .setDataId("app.yaml")
                .setContent("key: newValue")
                .setContentType("yaml")
                .setOperator("admin")
                .setDescription("initial")
                .build();

        TestStreamObserver<PublishConfigResponse> observer = new TestStreamObserver<>();
        service.publishConfig(request, observer);

        assertNotNull(observer.value);
        assertTrue(observer.value.getSuccess());
        assertEquals(1, observer.value.getVersion());
        assertEquals("abc123", observer.value.getMd5());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    @Test
    void publishConfig_failure_returnsError() {
        when(configManager.publishConfig(
                anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Storage write failed"));

        PublishConfigRequest request = PublishConfigRequest.newBuilder()
                .setNamespace("public")
                .setGroup("DEFAULT_GROUP")
                .setDataId("app.yaml")
                .setContent("bad content")
                .setContentType("text")
                .setOperator("admin")
                .setDescription("should fail")
                .build();

        TestStreamObserver<PublishConfigResponse> observer = new TestStreamObserver<>();
        service.publishConfig(request, observer);

        assertNotNull(observer.value);
        assertFalse(observer.value.getSuccess());
        assertEquals("Storage write failed", observer.value.getMessage());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    // -------- deleteConfig tests --------

    @Test
    void deleteConfig_found() {
        when(configManager.removeConfig("public", "app.yaml", "DEFAULT_GROUP", "admin")).thenReturn(true);

        DeleteConfigRequest request = DeleteConfigRequest.newBuilder()
                .setNamespace("public")
                .setGroup("DEFAULT_GROUP")
                .setDataId("app.yaml")
                .setOperator("admin")
                .build();

        TestStreamObserver<DeleteConfigResponse> observer = new TestStreamObserver<>();
        service.deleteConfig(request, observer);

        assertNotNull(observer.value);
        assertTrue(observer.value.getSuccess());
        assertEquals("OK", observer.value.getMessage());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }

    @Test
    void deleteConfig_notFound() {
        when(configManager.removeConfig("public", "ghost.yaml", "DEFAULT_GROUP", "admin")).thenReturn(false);

        DeleteConfigRequest request = DeleteConfigRequest.newBuilder()
                .setNamespace("public")
                .setGroup("DEFAULT_GROUP")
                .setDataId("ghost.yaml")
                .setOperator("admin")
                .build();

        TestStreamObserver<DeleteConfigResponse> observer = new TestStreamObserver<>();
        service.deleteConfig(request, observer);

        assertNotNull(observer.value);
        assertFalse(observer.value.getSuccess());
        assertEquals("Config not found", observer.value.getMessage());
        assertTrue(observer.completed);
        assertNull(observer.error);
    }
}
