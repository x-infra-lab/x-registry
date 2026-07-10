package com.x.registry.server;

import com.x.registry.api.config.ConfigService;
import com.x.registry.api.model.ConfigItem;
import com.x.registry.client.XRegistryClient;
import com.x.registry.client.XRegistryClientConfig;
import com.x.registry.server.boot.XRegistryServerApplication;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = XRegistryServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
class ConfigIntegrationTest {

    private XRegistryClient client;

    @BeforeEach
    void setUp() {
        XRegistryClientConfig config = new XRegistryClientConfig()
                .setServerAddr("127.0.0.1:9848");
        client = new XRegistryClient(config);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void testPublishAndGetConfig() {
        ConfigService configService = client.getConfigService();

        boolean success = configService.publishConfig("public", "app.yaml", "DEFAULT_GROUP",
                "server:\n  port: 8080", "yaml", "test", "test config");
        assertTrue(success);

        ConfigItem item = configService.getConfig("public", "app.yaml", "DEFAULT_GROUP");
        assertNotNull(item);
        assertEquals("app.yaml", item.getDataId());
        assertEquals("server:\n  port: 8080", item.getContent());
        assertNotNull(item.getMd5());
        assertTrue(item.getVersion() > 0);
    }

    @Test
    void testConfigHistory() {
        ConfigService configService = client.getConfigService();

        configService.publishConfig("public", "history-test.yaml", "DEFAULT_GROUP",
                "v1", "yaml", "test", "version 1");
        configService.publishConfig("public", "history-test.yaml", "DEFAULT_GROUP",
                "v2", "yaml", "test", "version 2");
        configService.publishConfig("public", "history-test.yaml", "DEFAULT_GROUP",
                "v3", "yaml", "test", "version 3");

        List<ConfigItem> history = configService.listHistory("public", "history-test.yaml", "DEFAULT_GROUP", 0, 10);
        assertTrue(history.size() >= 3);
    }

    @Test
    void testConfigWatch() throws Exception {
        ConfigService configService = client.getConfigService();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ConfigItem> received = new AtomicReference<>();

        configService.addListener("public", "watch-test.yaml", "DEFAULT_GROUP", item -> {
            received.set(item);
            latch.countDown();
        });

        Thread.sleep(500);

        configService.publishConfig("public", "watch-test.yaml", "DEFAULT_GROUP",
                "new content", "yaml", "test", "trigger watch");

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(received.get());
        assertEquals("new content", received.get().getContent());
    }

    @Test
    void testDeleteConfig() {
        ConfigService configService = client.getConfigService();

        configService.publishConfig("public", "delete-test.yaml", "DEFAULT_GROUP",
                "content", "yaml", "test", "to be deleted");

        boolean removed = configService.removeConfig("public", "delete-test.yaml", "DEFAULT_GROUP", "test");
        assertTrue(removed);

        ConfigItem item = configService.getConfig("public", "delete-test.yaml", "DEFAULT_GROUP");
        assertNull(item);
    }
}
