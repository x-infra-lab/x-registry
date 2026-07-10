package com.x.registry.server;

import com.x.registry.api.model.Instance;
import com.x.registry.api.naming.NamingService;
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
class NamingIntegrationTest {

    private XRegistryClient client;

    @BeforeEach
    void setUp() {
        XRegistryClientConfig config = new XRegistryClientConfig()
                .setServerAddr("127.0.0.1:9848")
                .setHeartbeatIntervalMs(2000);
        client = new XRegistryClient(config);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void testRegisterAndDiscover() {
        NamingService naming = client.getNamingService();

        Instance instance = new Instance();
        instance.setIp("192.168.1.100");
        instance.setPort(8080);
        instance.setWeight(1.0);
        instance.setEphemeral(true);

        naming.registerInstance("public", "demo-service", "DEFAULT_GROUP", instance);

        List<Instance> instances = naming.getInstances("public", "demo-service", "DEFAULT_GROUP", false);
        assertFalse(instances.isEmpty());
        assertEquals("192.168.1.100", instances.get(0).getIp());
        assertEquals(8080, instances.get(0).getPort());

        naming.deregisterInstance("public", "demo-service", "DEFAULT_GROUP", instance);

        instances = naming.getInstances("public", "demo-service", "DEFAULT_GROUP", false);
        assertTrue(instances.isEmpty());
    }

    @Test
    void testSubscribe() throws Exception {
        NamingService naming = client.getNamingService();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<Instance>> received = new AtomicReference<>();

        naming.subscribe("public", "push-test-service", "DEFAULT_GROUP", instances -> {
            if (!instances.isEmpty()) {
                received.set(instances);
                latch.countDown();
            }
        });

        Thread.sleep(500);

        Instance instance = new Instance();
        instance.setIp("10.0.0.1");
        instance.setPort(9090);
        instance.setEphemeral(true);
        naming.registerInstance("public", "push-test-service", "DEFAULT_GROUP", instance);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(received.get());
        assertFalse(received.get().isEmpty());
    }
}
