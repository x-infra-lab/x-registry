package com.x.registry.server.config;

import com.x.registry.api.model.ConfigItem;
import com.x.registry.server.audit.AuditLogger;
import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.storage.ConfigStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigManagerTest {

    @Mock
    private ConfigStore configStore;

    @Mock
    private ConfigWatcherManager watcherManager;

    @Mock
    private AuditLogger auditLogger;

    @Mock
    private ClusterManager clusterManager;

    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        // Use a real SimpleMeterRegistry so Counter/Gauge registration works
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        // Default: standalone mode (not clustered)
        lenient().when(clusterManager.isClustered()).thenReturn(false);

        configManager = new ConfigManager(configStore, watcherManager, auditLogger, meterRegistry, clusterManager);
    }

    // -----------------------------------------------------------------------
    // publishConfig
    // -----------------------------------------------------------------------

    @Test
    void publishConfig_storesConfigAndLogsAudit() {
        ConfigItem published = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        published.setContent("key=value");
        published.setVersion(1L);

        when(configStore.publish("public", "DEFAULT_GROUP", "app.properties",
                "key=value", "text", "admin", "initial"))
                .thenReturn(published);
        when(configStore.get("public", "DEFAULT_GROUP", "app.properties")).thenReturn(published);

        ConfigItem result = configManager.publishConfig("public", "app.properties", "DEFAULT_GROUP",
                "key=value", "text", "admin", "initial");

        assertNotNull(result);
        assertEquals("key=value", result.getContent());
        assertEquals(1L, result.getVersion());

        verify(configStore).publish("public", "DEFAULT_GROUP", "app.properties",
                "key=value", "text", "admin", "initial");
        verify(auditLogger).logConfigPublish("public", "app.properties", "DEFAULT_GROUP", "admin");
    }

    @Test
    void publishConfig_notifiesWatchers() {
        ConfigItem published = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        published.setContent("key=value");
        published.setVersion(1L);

        when(configStore.publish(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(published);
        when(configStore.get("public", "DEFAULT_GROUP", "app.properties")).thenReturn(published);

        configManager.publishConfig("public", "app.properties", "DEFAULT_GROUP",
                "key=value", "text", "admin", "test");

        verify(watcherManager).notifyWatchers("public", "DEFAULT_GROUP", "app.properties", published);
    }

    // -----------------------------------------------------------------------
    // getConfig
    // -----------------------------------------------------------------------

    @Test
    void getConfig_retrievesFromStore() {
        ConfigItem item = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        item.setContent("key=value");
        when(configStore.get("public", "DEFAULT_GROUP", "app.properties")).thenReturn(item);

        ConfigItem result = configManager.getConfig("public", "app.properties", "DEFAULT_GROUP");

        assertNotNull(result);
        assertEquals("key=value", result.getContent());
        verify(configStore).get("public", "DEFAULT_GROUP", "app.properties");
    }

    @Test
    void getConfig_returnsNullWhenNotFound() {
        when(configStore.get("public", "DEFAULT_GROUP", "missing.properties")).thenReturn(null);

        ConfigItem result = configManager.getConfig("public", "missing.properties", "DEFAULT_GROUP");

        assertNull(result);
    }

    // -----------------------------------------------------------------------
    // removeConfig (deleteConfig)
    // -----------------------------------------------------------------------

    @Test
    void removeConfig_removesAndLogsAudit() {
        when(configStore.remove("public", "DEFAULT_GROUP", "app.properties")).thenReturn(true);

        boolean removed = configManager.removeConfig("public", "app.properties", "DEFAULT_GROUP", "admin");

        assertTrue(removed);
        verify(configStore).remove("public", "DEFAULT_GROUP", "app.properties");
        verify(auditLogger).logConfigDelete("public", "app.properties", "DEFAULT_GROUP", "admin");
        verify(watcherManager).notifyWatchersDeleted("public", "DEFAULT_GROUP", "app.properties");
    }

    @Test
    void removeConfig_whenNotFound_returnsFalseAndDoesNotAudit() {
        when(configStore.remove("public", "DEFAULT_GROUP", "missing.properties")).thenReturn(false);

        boolean removed = configManager.removeConfig("public", "missing.properties", "DEFAULT_GROUP", "admin");

        assertFalse(removed);
        verify(auditLogger, never()).logConfigDelete(anyString(), anyString(), anyString(), anyString());
        verify(watcherManager, never()).notifyWatchersDeleted(anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // rollback
    // -----------------------------------------------------------------------

    @Test
    void rollback_toPreviousVersion_republishesOldContent() {
        // Simulate a previous version in the store
        ConfigItem targetVersion = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        targetVersion.setContent("old-content");
        targetVersion.setContentType("text");
        targetVersion.setVersion(1L);

        when(configStore.getVersion("public", "DEFAULT_GROUP", "app.properties", 1L))
                .thenReturn(targetVersion);

        // The rollback re-publishes, which calls configStore.publish and configStore.get
        ConfigItem republished = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        republished.setContent("old-content");
        republished.setContentType("text");
        republished.setVersion(3L);

        when(configStore.publish("public", "DEFAULT_GROUP", "app.properties",
                "old-content", "text", "rollback", "Rollback to version 1"))
                .thenReturn(republished);
        when(configStore.get("public", "DEFAULT_GROUP", "app.properties")).thenReturn(republished);

        ConfigItem result = configManager.rollback("public", "app.properties", "DEFAULT_GROUP", 1L);

        assertNotNull(result);
        assertEquals("old-content", result.getContent());
        assertEquals(3L, result.getVersion());

        verify(configStore).getVersion("public", "DEFAULT_GROUP", "app.properties", 1L);
        verify(configStore).publish("public", "DEFAULT_GROUP", "app.properties",
                "old-content", "text", "rollback", "Rollback to version 1");
    }

    @Test
    void rollback_targetVersionNotFound_returnsNull() {
        when(configStore.getVersion("public", "DEFAULT_GROUP", "app.properties", 99L))
                .thenReturn(null);

        ConfigItem result = configManager.rollback("public", "app.properties", "DEFAULT_GROUP", 99L);

        assertNull(result);
        verify(configStore, never()).publish(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // Version increments on each publish
    // -----------------------------------------------------------------------

    @Test
    void publishConfig_versionIncrementsOnSuccessivePublishes() {
        ConfigItem v1 = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        v1.setContent("v1");
        v1.setVersion(1L);

        ConfigItem v2 = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        v2.setContent("v2");
        v2.setVersion(2L);

        ConfigItem v3 = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        v3.setContent("v3");
        v3.setVersion(3L);

        when(configStore.publish(eq("public"), eq("DEFAULT_GROUP"), eq("app.properties"),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(v1, v2, v3);
        when(configStore.get("public", "DEFAULT_GROUP", "app.properties"))
                .thenReturn(v1, v2, v3);

        ConfigItem result1 = configManager.publishConfig("public", "app.properties", "DEFAULT_GROUP",
                "v1", "text", "admin", "first");
        ConfigItem result2 = configManager.publishConfig("public", "app.properties", "DEFAULT_GROUP",
                "v2", "text", "admin", "second");
        ConfigItem result3 = configManager.publishConfig("public", "app.properties", "DEFAULT_GROUP",
                "v3", "text", "admin", "third");

        assertEquals(1L, result1.getVersion());
        assertEquals(2L, result2.getVersion());
        assertEquals(3L, result3.getVersion());

        // publish is called 3 times total
        verify(configStore, times(3)).publish(eq("public"), eq("DEFAULT_GROUP"), eq("app.properties"),
                anyString(), anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // listHistory / getHistoryCount
    // -----------------------------------------------------------------------

    @Test
    void listHistory_delegatesToConfigStore() {
        ConfigItem h1 = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        h1.setVersion(2L);
        ConfigItem h2 = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        h2.setVersion(1L);

        when(configStore.listHistory("public", "DEFAULT_GROUP", "app.properties", 0, 10))
                .thenReturn(List.of(h1, h2));

        List<ConfigItem> history = configManager.listHistory("public", "app.properties", "DEFAULT_GROUP", 0, 10);

        assertEquals(2, history.size());
        assertEquals(2L, history.get(0).getVersion());
        assertEquals(1L, history.get(1).getVersion());
    }

    @Test
    void getHistoryCount_delegatesToConfigStore() {
        when(configStore.getHistoryCount("public", "DEFAULT_GROUP", "app.properties")).thenReturn(5);

        int count = configManager.getHistoryCount("public", "app.properties", "DEFAULT_GROUP");

        assertEquals(5, count);
    }
}
