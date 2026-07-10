package com.x.registry.spring;

import com.x.registry.api.config.ConfigService;
import com.x.registry.api.naming.NamingService;
import com.x.registry.client.XRegistryClient;
import com.x.registry.client.XRegistryClientConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(XRegistryProperties.class)
public class XRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public XRegistryClient xRegistryClient(XRegistryProperties properties) {
        XRegistryClientConfig config = new XRegistryClientConfig()
                .setServerAddr(properties.getServerAddr())
                .setNamespace(properties.getNamespace());
        return new XRegistryClient(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public NamingService xRegistryNamingService(XRegistryClient client) {
        return client.getNamingService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigService xRegistryConfigService(XRegistryClient client) {
        return client.getConfigService();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "x-registry.discovery", name = "enabled", matchIfMissing = true)
    public XRegistryDiscoveryClient xRegistryDiscoveryClient(NamingService namingService, XRegistryProperties properties) {
        return new XRegistryDiscoveryClient(namingService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "x-registry.config", name = "enabled", matchIfMissing = true)
    public XRegistryConfigClient xRegistryConfigClient(ConfigService configService, XRegistryProperties properties) {
        return new XRegistryConfigClient(configService, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "x-registry.discovery", name = "register", matchIfMissing = true)
    public XRegistryAutoRegistration xRegistryAutoRegistration(NamingService namingService, XRegistryProperties properties) {
        return new XRegistryAutoRegistration(namingService, properties);
    }
}
