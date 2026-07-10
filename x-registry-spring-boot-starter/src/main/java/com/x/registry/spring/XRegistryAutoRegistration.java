package com.x.registry.spring;

import com.x.registry.api.model.Instance;
import com.x.registry.api.naming.NamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.net.InetAddress;

public class XRegistryAutoRegistration implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(XRegistryAutoRegistration.class);

    private final NamingService namingService;
    private final XRegistryProperties properties;
    private volatile boolean running = false;
    private Instance registeredInstance;

    public XRegistryAutoRegistration(NamingService namingService, XRegistryProperties properties) {
        this.namingService = namingService;
        this.properties = properties;
    }

    @Override
    public void start() {
        XRegistryProperties.Discovery discovery = properties.getDiscovery();
        if (!discovery.isEnabled() || !discovery.isRegister()) {
            log.info("X-Registry auto-registration is disabled");
            return;
        }

        String serviceName = discovery.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            log.warn("x-registry.discovery.service-name is not set, skipping registration");
            return;
        }

        String ip = resolveIp(discovery.getIp());
        int port = discovery.getPort();
        if (port <= 0) {
            log.warn("x-registry.discovery.port is not set, skipping registration");
            return;
        }

        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(discovery.getWeight());
        instance.setEphemeral(discovery.isEphemeral());

        try {
            namingService.registerInstance(properties.getNamespace(), serviceName, properties.getGroup(), instance);
            this.registeredInstance = instance;
            this.running = true;
            log.info("Registered service [{}] at {}:{} to X-Registry", serviceName, ip, port);
        } catch (Exception e) {
            log.error("Failed to register service [{}] to X-Registry", serviceName, e);
        }
    }

    @Override
    public void stop() {
        if (!running || registeredInstance == null) {
            return;
        }

        XRegistryProperties.Discovery discovery = properties.getDiscovery();
        String serviceName = discovery.getServiceName();

        try {
            namingService.deregisterInstance(properties.getNamespace(), serviceName, properties.getGroup(), registeredInstance);
            log.info("Deregistered service [{}] from X-Registry", serviceName);
        } catch (Exception e) {
            log.warn("Failed to deregister service [{}] from X-Registry", serviceName, e);
        }
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    private String resolveIp(String configuredIp) {
        if (configuredIp != null && !configuredIp.isBlank()) {
            return configuredIp;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("Failed to resolve local IP, falling back to 127.0.0.1", e);
            return "127.0.0.1";
        }
    }
}
