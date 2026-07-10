package com.x.registry.spring;

import com.x.registry.api.config.ConfigService;
import com.x.registry.api.model.ConfigItem;

import java.util.function.Consumer;

public class XRegistryConfigClient {

    private final ConfigService configService;
    private final XRegistryProperties properties;

    public XRegistryConfigClient(ConfigService configService, XRegistryProperties properties) {
        this.configService = configService;
        this.properties = properties;
    }

    public String getConfig(String dataId) {
        ConfigItem item = configService.getConfig(properties.getNamespace(), dataId, properties.getGroup());
        return item != null ? item.getContent() : null;
    }

    public ConfigItem getConfigItem(String dataId) {
        return configService.getConfig(properties.getNamespace(), dataId, properties.getGroup());
    }

    public boolean publishConfig(String dataId, String content, String contentType) {
        return configService.publishConfig(properties.getNamespace(), dataId, properties.getGroup(),
                content, contentType, "spring-boot-app", null);
    }

    public boolean removeConfig(String dataId) {
        return configService.removeConfig(properties.getNamespace(), dataId, properties.getGroup(), "spring-boot-app");
    }

    public void addListener(String dataId, Consumer<ConfigItem> listener) {
        configService.addListener(properties.getNamespace(), dataId, properties.getGroup(), listener);
    }

    public void removeListener(String dataId, Consumer<ConfigItem> listener) {
        configService.removeListener(properties.getNamespace(), dataId, properties.getGroup(), listener);
    }
}
