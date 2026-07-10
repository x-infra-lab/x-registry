package com.x.registry.server.cluster.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.x.registry.api.model.ConfigItem;
import com.x.registry.api.model.Instance;
import com.x.registry.server.config.ConfigWatcherManager;
import com.x.registry.server.storage.ConfigStore;
import com.x.registry.server.storage.InstanceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigStateMachineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ConfigStore configStore;

    @Mock
    private InstanceStore instanceStore;

    @Mock
    private ConfigWatcherManager watcherManager;

    private ConfigStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new ConfigStateMachine(configStore, instanceStore);
    }

    // -----------------------------------------------------------------------
    // onApply: CONFIG_PUBLISH
    // -----------------------------------------------------------------------

    @Test
    void onApply_configPublish_callsConfigStorePublish() throws Exception {
        Map<String, String> data = Map.of(
                "namespace", "public",
                "group", "DEFAULT_GROUP",
                "dataId", "app.properties",
                "content", "key=value",
                "contentType", "text",
                "operator", "admin",
                "description", "initial publish"
        );
        byte[] jsonBytes = MAPPER.writeValueAsBytes(data);
        LogEntry entry = new LogEntry(1L, 1L, LogEntry.Type.CONFIG_PUBLISH, jsonBytes);

        stateMachine.onApply(entry);

        verify(configStore).publish("public", "DEFAULT_GROUP", "app.properties",
                "key=value", "text", "admin", "initial publish");
    }

    @Test
    void onApply_configPublish_withWatcherManager_notifiesWatchers() throws Exception {
        stateMachine.setWatcherManager(watcherManager);

        ConfigItem publishedItem = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        publishedItem.setContent("key=value");
        when(configStore.get("public", "DEFAULT_GROUP", "app.properties")).thenReturn(publishedItem);

        Map<String, String> data = Map.of(
                "namespace", "public",
                "group", "DEFAULT_GROUP",
                "dataId", "app.properties",
                "content", "key=value",
                "contentType", "text",
                "operator", "admin",
                "description", "publish with watcher"
        );
        byte[] jsonBytes = MAPPER.writeValueAsBytes(data);
        LogEntry entry = new LogEntry(1L, 1L, LogEntry.Type.CONFIG_PUBLISH, jsonBytes);

        stateMachine.onApply(entry);

        verify(configStore).publish(eq("public"), eq("DEFAULT_GROUP"), eq("app.properties"),
                eq("key=value"), eq("text"), eq("admin"), eq("publish with watcher"));
        verify(watcherManager).notifyWatchers("public", "DEFAULT_GROUP", "app.properties", publishedItem);
    }

    @Test
    void onApply_configPublish_withWatcherManager_noItemReturned_doesNotNotify() throws Exception {
        stateMachine.setWatcherManager(watcherManager);

        when(configStore.get("public", "DEFAULT_GROUP", "missing.properties")).thenReturn(null);

        Map<String, String> data = Map.of(
                "namespace", "public",
                "group", "DEFAULT_GROUP",
                "dataId", "missing.properties",
                "content", "x=y",
                "contentType", "text",
                "operator", "admin",
                "description", ""
        );
        byte[] jsonBytes = MAPPER.writeValueAsBytes(data);
        LogEntry entry = new LogEntry(1L, 1L, LogEntry.Type.CONFIG_PUBLISH, jsonBytes);

        stateMachine.onApply(entry);

        verify(watcherManager, never()).notifyWatchers(anyString(), anyString(), anyString(), any(ConfigItem.class));
    }

    // -----------------------------------------------------------------------
    // onApply: CONFIG_DELETE
    // -----------------------------------------------------------------------

    @Test
    void onApply_configDelete_callsConfigStoreRemove() throws Exception {
        Map<String, String> data = Map.of(
                "namespace", "public",
                "group", "DEFAULT_GROUP",
                "dataId", "app.properties"
        );
        byte[] jsonBytes = MAPPER.writeValueAsBytes(data);
        LogEntry entry = new LogEntry(2L, 1L, LogEntry.Type.CONFIG_DELETE, jsonBytes);

        stateMachine.onApply(entry);

        verify(configStore).remove("public", "DEFAULT_GROUP", "app.properties");
    }

    @Test
    void onApply_configDelete_withWatcherManager_notifiesWatchersDeleted() throws Exception {
        stateMachine.setWatcherManager(watcherManager);

        Map<String, String> data = Map.of(
                "namespace", "ns1",
                "group", "grp1",
                "dataId", "db.yaml"
        );
        byte[] jsonBytes = MAPPER.writeValueAsBytes(data);
        LogEntry entry = new LogEntry(3L, 1L, LogEntry.Type.CONFIG_DELETE, jsonBytes);

        stateMachine.onApply(entry);

        verify(configStore).remove("ns1", "grp1", "db.yaml");
        verify(watcherManager).notifyWatchersDeleted("ns1", "grp1", "db.yaml");
    }

    // -----------------------------------------------------------------------
    // onApply: INSTANCE_REGISTER_PERSISTENT / INSTANCE_DEREGISTER_PERSISTENT
    // -----------------------------------------------------------------------

    @Test
    void onApply_instanceRegisterPersistent_callsInstanceStoreRegister() throws Exception {
        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(8080);
        instance.setEphemeral(false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("namespace", "public");
        data.put("group", "DEFAULT_GROUP");
        data.put("serviceName", "order-service");
        data.put("instance", instance);
        byte[] jsonBytes = MAPPER.writeValueAsBytes(data);

        LogEntry entry = new LogEntry(4L, 1L, LogEntry.Type.INSTANCE_REGISTER_PERSISTENT, jsonBytes);

        stateMachine.onApply(entry);

        verify(instanceStore).register(eq("public"), eq("DEFAULT_GROUP"), eq("order-service"), any(Instance.class));
    }

    @Test
    void onApply_instanceDeregisterPersistent_callsInstanceStoreDeregister() throws Exception {
        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(8080);
        instance.setEphemeral(false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("namespace", "public");
        data.put("group", "DEFAULT_GROUP");
        data.put("serviceName", "order-service");
        data.put("instance", instance);
        byte[] jsonBytes = MAPPER.writeValueAsBytes(data);

        LogEntry entry = new LogEntry(5L, 1L, LogEntry.Type.INSTANCE_DEREGISTER_PERSISTENT, jsonBytes);

        stateMachine.onApply(entry);

        verify(instanceStore).deregister(eq("public"), eq("DEFAULT_GROUP"), eq("order-service"), any(Instance.class));
    }

    @Test
    void onApply_instanceRegisterPersistent_noInstanceStore_doesNothing() throws Exception {
        ConfigStateMachine machineNoInstStore = new ConfigStateMachine(configStore);

        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(8080);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("namespace", "public");
        data.put("group", "DEFAULT_GROUP");
        data.put("serviceName", "svc");
        data.put("instance", instance);
        byte[] jsonBytes = MAPPER.writeValueAsBytes(data);

        LogEntry entry = new LogEntry(6L, 1L, LogEntry.Type.INSTANCE_REGISTER_PERSISTENT, jsonBytes);

        // Should not throw; instanceStore is null so it skips
        machineNoInstStore.onApply(entry);

        verifyNoInteractions(instanceStore);
    }

    // -----------------------------------------------------------------------
    // Snapshot save / load roundtrip
    // -----------------------------------------------------------------------

    @Test
    void snapshotSaveAndLoad_roundtripPreservesConfigData() throws Exception {
        // Prepare config items returned by configStore.listAll()
        ConfigItem item1 = new ConfigItem("public", "DEFAULT_GROUP", "app.properties");
        item1.setContent("key1=val1");
        item1.setContentType("text");
        item1.setVersion(1L);

        ConfigItem item2 = new ConfigItem("ns2", "grp2", "db.yaml");
        item2.setContent("host: localhost");
        item2.setContentType("yaml");
        item2.setVersion(2L);

        List<ConfigItem> allConfigs = new ArrayList<>();
        allConfigs.add(item1);
        allConfigs.add(item2);
        when(configStore.listAll()).thenReturn(allConfigs);

        // No persistent instances
        when(instanceStore.getAllPersistentInstances()).thenReturn(Map.of());

        // Save snapshot
        byte[] snapshot = stateMachine.onSnapshotSave();
        assertNotNull(snapshot);
        assertTrue(snapshot.length > 0);

        // Reset mocks for load verification
        reset(configStore, instanceStore);

        // Load snapshot into a fresh state machine
        ConfigStateMachine newStateMachine = new ConfigStateMachine(configStore, instanceStore);
        newStateMachine.onSnapshotLoad(snapshot);

        // Verify that publish was called for each config entry during restore
        verify(configStore).publish(eq("public"), eq("DEFAULT_GROUP"), eq("app.properties"),
                eq("key1=val1"), eq("text"), eq("snapshot-restore"), eq("Restored from Raft snapshot"));
        verify(configStore).publish(eq("ns2"), eq("grp2"), eq("db.yaml"),
                eq("host: localhost"), eq("yaml"), eq("snapshot-restore"), eq("Restored from Raft snapshot"));
        verify(configStore, times(2)).publish(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void snapshotSaveAndLoad_emptyStore_producesValidSnapshot() throws Exception {
        when(configStore.listAll()).thenReturn(List.of());
        when(instanceStore.getAllPersistentInstances()).thenReturn(Map.of());

        byte[] snapshot = stateMachine.onSnapshotSave();
        assertNotNull(snapshot);
        assertTrue(snapshot.length > 0);

        // Load into a new machine - no publish calls expected
        reset(configStore, instanceStore);
        ConfigStateMachine newStateMachine = new ConfigStateMachine(configStore, instanceStore);
        newStateMachine.onSnapshotLoad(snapshot);

        verify(configStore, never()).publish(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // onLeaderStart / onLeaderStop
    // -----------------------------------------------------------------------

    @Test
    void onLeaderStart_setsIsLeaderTrue() {
        assertFalse(stateMachine.isLeader());

        stateMachine.onLeaderStart(1L);

        assertTrue(stateMachine.isLeader());
    }

    @Test
    void onLeaderStop_setsIsLeaderFalse() {
        stateMachine.onLeaderStart(1L);
        assertTrue(stateMachine.isLeader());

        stateMachine.onLeaderStop();

        assertFalse(stateMachine.isLeader());
    }

    @Test
    void leaderToggle_multipleTransitions() {
        assertFalse(stateMachine.isLeader());

        stateMachine.onLeaderStart(1L);
        assertTrue(stateMachine.isLeader());

        stateMachine.onLeaderStop();
        assertFalse(stateMachine.isLeader());

        stateMachine.onLeaderStart(2L);
        assertTrue(stateMachine.isLeader());
    }
}
