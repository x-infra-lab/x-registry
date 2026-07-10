package com.example.demo;

import com.x.registry.api.model.Instance;
import com.x.registry.spring.XRegistryConfigClient;
import com.x.registry.spring.XRegistryDiscoveryClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@SpringBootApplication
@RestController
public class DemoApplication {

    private final XRegistryDiscoveryClient discoveryClient;
    private final XRegistryConfigClient configClient;

    public DemoApplication(XRegistryDiscoveryClient discoveryClient, XRegistryConfigClient configClient) {
        this.discoveryClient = discoveryClient;
        this.configClient = configClient;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @GetMapping("/discover/{serviceName}")
    public List<Instance> discover(@PathVariable String serviceName) {
        return discoveryClient.getInstances(serviceName);
    }

    @GetMapping("/config/{dataId}")
    public String getConfig(@PathVariable String dataId) {
        return configClient.getConfig(dataId);
    }
}
