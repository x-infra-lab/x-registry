package com.x.registry.client;

import com.x.registry.api.config.ConfigService;
import com.x.registry.api.naming.NamingService;
import com.x.registry.client.cache.LocalCacheManager;
import com.x.registry.client.config.ConfigServiceImpl;
import com.x.registry.client.connection.ConnectionManager;
import com.x.registry.client.event.EventBus;
import com.x.registry.client.naming.NamingServiceImpl;

public class XRegistryClient implements AutoCloseable {

    private final XRegistryClientConfig config;
    private final ConnectionManager connectionManager;
    private final LocalCacheManager cacheManager;
    private final NamingServiceImpl namingService;
    private final ConfigServiceImpl configService;
    private final EventBus eventBus;

    public XRegistryClient(XRegistryClientConfig config) {
        this.config = config;
        this.eventBus = new EventBus();
        this.connectionManager = new ConnectionManager(config, eventBus);
        this.cacheManager = new LocalCacheManager(config.getCacheDir());
        this.namingService = new NamingServiceImpl(config, connectionManager, cacheManager, eventBus);
        this.configService = new ConfigServiceImpl(config, connectionManager, cacheManager, eventBus);
    }

    public static XRegistryClient create(String serverAddr) {
        XRegistryClientConfig config = new XRegistryClientConfig().setServerAddr(serverAddr);
        return new XRegistryClient(config);
    }

    public NamingService getNamingService() {
        return namingService;
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public XRegistryClientConfig getConfig() {
        return config;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public void close() {
        namingService.close();
        configService.close();
        connectionManager.close();
        eventBus.shutdown();
    }
}
