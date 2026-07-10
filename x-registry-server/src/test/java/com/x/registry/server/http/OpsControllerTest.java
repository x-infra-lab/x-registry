package com.x.registry.server.http;

import com.x.registry.api.model.ConfigItem;
import com.x.registry.server.config.ConfigManager;
import com.x.registry.server.naming.ConnectionRegistry;
import com.x.registry.server.naming.ServiceManager;
import com.x.registry.server.storage.ConfigStore;
import com.x.registry.server.storage.InstanceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpsControllerTest {

    @Mock
    private InstanceStore instanceStore;

    @Mock
    private ConfigStore configStore;

    @Mock
    private ServiceManager serviceManager;

    @Mock
    private ConfigManager configManager;

    @Mock
    private ConnectionRegistry connectionRegistry;

    private OpsController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsController(instanceStore, configStore, serviceManager, configManager, connectionRegistry);
    }

    @Test
    void exportConfigs_noFilter_returnsAll() {
        ConfigItem c1 = new ConfigItem("public", "DEFAULT_GROUP", "app.yaml");
        c1.setContent("key: value1");
        c1.setContentType("yaml");

        ConfigItem c2 = new ConfigItem("dev", "DEFAULT_GROUP", "db.properties");
        c2.setContent("url=jdbc:mysql://localhost");
        c2.setContentType("properties");

        when(configStore.listAll()).thenReturn(Arrays.asList(c1, c2));

        Map<String, Object> result = controller.exportConfigs("").block();

        assertNotNull(result);
        assertEquals(2, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>) result.get("configs");
        assertEquals(2, configs.size());
        verify(configStore).listAll();
    }

    @Test
    void exportConfigs_withNamespace_filters() {
        ConfigItem c1 = new ConfigItem("public", "DEFAULT_GROUP", "app.yaml");
        c1.setContent("key: value1");
        c1.setContentType("yaml");

        ConfigItem c2 = new ConfigItem("dev", "DEFAULT_GROUP", "db.properties");
        c2.setContent("url=jdbc:mysql://localhost");
        c2.setContentType("properties");

        when(configStore.listAll()).thenReturn(Arrays.asList(c1, c2));

        Map<String, Object> result = controller.exportConfigs("dev").block();

        assertNotNull(result);
        assertEquals(1, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>) result.get("configs");
        assertEquals(1, configs.size());
        assertEquals("dev", configs.get(0).get("namespace"));
        assertEquals("db.properties", configs.get(0).get("dataId"));
    }

    @Test
    void importConfigs_success() {
        OpsController.ConfigImportItem item1 = new OpsController.ConfigImportItem(
                "public", "DEFAULT_GROUP", "app.yaml", "key: value", "yaml");
        OpsController.ConfigImportItem item2 = new OpsController.ConfigImportItem(
                "dev", "DEFAULT_GROUP", "db.properties", "url=jdbc:mysql://localhost", "properties");
        OpsController.ConfigImportRequest request = new OpsController.ConfigImportRequest(Arrays.asList(item1, item2));

        ConfigItem published = new ConfigItem("public", "DEFAULT_GROUP", "app.yaml");
        published.setContent("key: value");
        when(configManager.publishConfig(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(published);

        Map<String, Object> result = controller.importConfigs(request).block();

        assertNotNull(result);
        assertEquals(2, result.get("success"));
        assertEquals(0, result.get("failed"));
        assertEquals(2, result.get("total"));
        verify(configManager, times(2)).publishConfig(anyString(), anyString(), anyString(), anyString(), anyString(), eq("ops-import"), eq("Imported via ops API"));
    }

    @Test
    void importConfigs_partialFailure() {
        OpsController.ConfigImportItem item1 = new OpsController.ConfigImportItem(
                "public", "DEFAULT_GROUP", "app.yaml", "key: value", "yaml");
        OpsController.ConfigImportItem item2 = new OpsController.ConfigImportItem(
                "dev", "DEFAULT_GROUP", "db.properties", "url=jdbc:mysql://localhost", "properties");
        OpsController.ConfigImportRequest request = new OpsController.ConfigImportRequest(Arrays.asList(item1, item2));

        ConfigItem published = new ConfigItem("public", "DEFAULT_GROUP", "app.yaml");
        published.setContent("key: value");

        when(configManager.publishConfig(eq("public"), eq("app.yaml"), eq("DEFAULT_GROUP"),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(published);
        when(configManager.publishConfig(eq("dev"), eq("db.properties"), eq("DEFAULT_GROUP"),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Raft commit failed"));

        Map<String, Object> result = controller.importConfigs(request).block();

        assertNotNull(result);
        assertEquals(1, result.get("success"));
        assertEquals(1, result.get("failed"));
        assertEquals(2, result.get("total"));
    }

    @Test
    void metricsSummary_returnsMetrics() {
        when(connectionRegistry.getActiveConnectionCount()).thenReturn(42);

        List<ConfigItem> configs = Arrays.asList(
                new ConfigItem("public", "DEFAULT_GROUP", "a.yaml"),
                new ConfigItem("public", "DEFAULT_GROUP", "b.yaml"),
                new ConfigItem("dev", "DEFAULT_GROUP", "c.yaml")
        );
        when(configStore.listAll()).thenReturn(configs);

        when(instanceStore.getAllServiceKeys()).thenReturn(Arrays.asList(
                "public@@DEFAULT_GROUP@@svc1",
                "public@@DEFAULT_GROUP@@svc2"
        ));

        Map<String, Object> result = controller.metricsSummary().block();

        assertNotNull(result);
        assertEquals(42, result.get("activeConnections"));
        assertEquals(3, result.get("totalConfigs"));
        assertEquals(2, result.get("totalServices"));
        assertNotNull(result.get("timestamp"));
    }
}
