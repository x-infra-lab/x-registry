package com.x.registry.server.naming;

import com.x.registry.api.model.Instance;
import com.x.registry.server.storage.InstanceStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class HealthCheckManager implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckManager.class);

    private static final long UNHEALTHY_THRESHOLD_MS = 15_000;
    private static final long REMOVE_THRESHOLD_MS = 30_000;
    private static final long CHECK_INTERVAL_MS = 5_000;
    private static final long PROBE_INTERVAL_MS = 10_000;
    private static final int PROBE_TIMEOUT_MS = 3_000;
    private static final int PROBE_FAIL_THRESHOLD = 3;

    private final InstanceStore instanceStore;
    private final ServiceManager serviceManager;
    private final Counter healthCheckCounter;
    private final Counter expiredCounter;
    private final Counter probeCounter;
    private final Counter probeFailCounter;
    private final ConcurrentHashMap<String, AtomicInteger> probeFailures = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public HealthCheckManager(InstanceStore instanceStore, ServiceManager serviceManager, MeterRegistry registry) {
        this.instanceStore = instanceStore;
        this.serviceManager = serviceManager;
        this.healthCheckCounter = Counter.builder("x_registry_health_check_total")
                .description("Total health check cycles executed")
                .register(registry);
        this.expiredCounter = Counter.builder("x_registry_instance_expired_total")
                .description("Total instances expired by health check")
                .register(registry);
        this.probeCounter = Counter.builder("x_registry_probe_total")
                .description("Total active probes executed")
                .register(registry);
        this.probeFailCounter = Counter.builder("x_registry_probe_fail_total")
                .description("Total active probe failures")
                .register(registry);
    }

    @Override
    public void afterPropertiesSet() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "health-check");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkEphemeral, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::probePersistent, PROBE_INTERVAL_MS, PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("HealthCheckManager started, ephemeral check={}ms, persistent probe={}ms", CHECK_INTERVAL_MS, PROBE_INTERVAL_MS);
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private void checkEphemeral() {
        try {
            healthCheckCounter.increment();
            long now = System.currentTimeMillis();
            Map<String, Instance> ephemeralInstances = instanceStore.getAllEphemeralInstances();

            for (Map.Entry<String, Instance> entry : ephemeralInstances.entrySet()) {
                Instance instance = entry.getValue();
                long elapsed = now - instance.getLastHeartbeat();

                if (elapsed > REMOVE_THRESHOLD_MS) {
                    expiredCounter.increment();
                    serviceManager.removeExpiredInstance(entry.getKey(), instance);
                } else if (elapsed > UNHEALTHY_THRESHOLD_MS && instance.isHealthy()) {
                    String[] parts = entry.getKey().split("@@");
                    serviceManager.markUnhealthy(parts[0], parts[1], parts[2], instance);
                }
            }
        } catch (Exception e) {
            log.error("Ephemeral health check error", e);
        }
    }

    private void probePersistent() {
        try {
            Map<String, Instance> persistentInstances = instanceStore.getAllPersistentInstances();
            if (persistentInstances.isEmpty()) {
                return;
            }

            for (Map.Entry<String, Instance> entry : persistentInstances.entrySet()) {
                Instance instance = entry.getValue();
                probeCounter.increment();
                boolean reachable = tcpProbe(instance.getIp(), instance.getPort());

                String instanceKey = entry.getKey();
                if (reachable) {
                    probeFailures.remove(instanceKey);
                    if (!instance.isHealthy()) {
                        String[] parts = instanceKey.split("@@");
                        if (parts.length >= 3) {
                            serviceManager.markHealthy(parts[0], parts[1], parts[2], instance);
                        }
                    }
                } else {
                    probeFailCounter.increment();
                    AtomicInteger failures = probeFailures.computeIfAbsent(instanceKey, k -> new AtomicInteger(0));
                    int count = failures.incrementAndGet();
                    if (count >= PROBE_FAIL_THRESHOLD && instance.isHealthy()) {
                        String[] parts = instanceKey.split("@@");
                        if (parts.length >= 3) {
                            log.warn("Persistent instance {}:{} failed {} probes, marking unhealthy",
                                    instance.getIp(), instance.getPort(), count);
                            serviceManager.markUnhealthy(parts[0], parts[1], parts[2], instance);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Persistent probe error", e);
        }
    }

    private boolean tcpProbe(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
