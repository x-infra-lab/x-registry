package com.x.registry.server.http;

import com.x.registry.api.model.ConfigItem;
import com.x.registry.api.model.Instance;
import com.x.registry.server.config.ConfigManager;
import com.x.registry.server.naming.ConnectionRegistry;
import com.x.registry.server.naming.ServiceManager;
import com.x.registry.server.storage.ConfigStore;
import com.x.registry.server.storage.InstanceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/ops")
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);

    private final InstanceStore instanceStore;
    private final ConfigStore configStore;
    private final ServiceManager serviceManager;
    private final ConfigManager configManager;
    private final ConnectionRegistry connectionRegistry;

    public OpsController(InstanceStore instanceStore, ConfigStore configStore,
                         ServiceManager serviceManager, ConfigManager configManager,
                         ConnectionRegistry connectionRegistry) {
        this.instanceStore = instanceStore;
        this.configStore = configStore;
        this.serviceManager = serviceManager;
        this.configManager = configManager;
        this.connectionRegistry = connectionRegistry;
    }

    @GetMapping("/export/configs")
    public Mono<Map<String, Object>> exportConfigs(
            @RequestParam(defaultValue = "") String namespace) {
        List<ConfigItem> all = configStore.listAll();
        List<Map<String, Object>> exported = all.stream()
                .filter(c -> namespace.isEmpty() || namespace.equals(c.getNamespace()))
                .map(c -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("namespace", c.getNamespace());
                    item.put("group", c.getGroup());
                    item.put("dataId", c.getDataId());
                    item.put("content", c.getContent());
                    item.put("contentType", c.getContentType());
                    return item;
                })
                .collect(Collectors.toList());

        return Mono.just(Map.of(
                "exportTime", System.currentTimeMillis(),
                "count", exported.size(),
                "configs", exported
        ));
    }

    @PostMapping("/import/configs")
    public Mono<Map<String, Object>> importConfigs(@RequestBody ConfigImportRequest request) {
        int success = 0;
        int failed = 0;

        for (ConfigImportItem item : request.configs()) {
            try {
                String ns = item.namespace() != null ? item.namespace() : "public";
                String group = item.group() != null ? item.group() : "DEFAULT_GROUP";
                configManager.publishConfig(ns, item.dataId(), group,
                        item.content(), item.contentType(), "ops-import", "Imported via ops API");
                success++;
            } catch (Exception e) {
                log.warn("Failed to import config: {}", item.dataId(), e);
                failed++;
            }
        }

        return Mono.just(Map.of(
                "success", success,
                "failed", failed,
                "total", request.configs().size()
        ));
    }

    @GetMapping("/export/services")
    public Mono<Map<String, Object>> exportServices(
            @RequestParam(defaultValue = "") String namespace) {
        List<String> allKeys = instanceStore.getAllServiceKeys();
        List<Map<String, Object>> exported = new ArrayList<>();

        for (String key : allKeys) {
            String[] parts = key.split("@@");
            if (parts.length != 3) continue;
            String ns = parts[0];
            String group = parts[1];
            String name = parts[2];
            if (!namespace.isEmpty() && !namespace.equals(ns)) continue;

            List<Instance> instances = instanceStore.getInstances(ns, group, name, false);
            List<Map<String, Object>> instList = instances.stream()
                    .filter(i -> !i.isEphemeral())
                    .map(i -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ip", i.getIp());
                        m.put("port", i.getPort());
                        m.put("weight", i.getWeight());
                        m.put("clusterName", i.getClusterName());
                        m.put("metadata", i.getMetadata());
                        return m;
                    })
                    .collect(Collectors.toList());

            if (!instList.isEmpty()) {
                Map<String, Object> svc = new LinkedHashMap<>();
                svc.put("namespace", ns);
                svc.put("group", group);
                svc.put("serviceName", name);
                svc.put("instances", instList);
                exported.add(svc);
            }
        }

        return Mono.just(Map.of(
                "exportTime", System.currentTimeMillis(),
                "count", exported.size(),
                "services", exported
        ));
    }

    @PostMapping("/import/services")
    public Mono<Map<String, Object>> importServices(@RequestBody ServiceImportRequest request) {
        int success = 0;
        int failed = 0;

        for (ServiceImportItem item : request.services()) {
            String ns = item.namespace() != null ? item.namespace() : "public";
            String group = item.group() != null ? item.group() : "DEFAULT_GROUP";

            for (InstanceImportItem inst : item.instances()) {
                try {
                    Instance instance = new Instance();
                    instance.setIp(inst.ip());
                    instance.setPort(inst.port());
                    instance.setWeight(inst.weight() > 0 ? inst.weight() : 1.0);
                    instance.setClusterName(inst.clusterName() != null ? inst.clusterName() : "DEFAULT");
                    instance.setEphemeral(false);
                    instance.setEnabled(true);
                    if (inst.metadata() != null) {
                        instance.setMetadata(inst.metadata());
                    }
                    serviceManager.registerInstance(ns, item.serviceName(), group, instance);
                    success++;
                } catch (Exception e) {
                    log.warn("Failed to import instance: {}:{}", inst.ip(), inst.port(), e);
                    failed++;
                }
            }
        }

        return Mono.just(Map.of(
                "success", success,
                "failed", failed,
                "total", success + failed
        ));
    }

    @GetMapping("/metrics/summary")
    public Mono<Map<String, Object>> metricsSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("activeConnections", connectionRegistry.getActiveConnectionCount());
        summary.put("totalConfigs", configStore.listAll().size());
        summary.put("totalServices", instanceStore.getAllServiceKeys().size());
        summary.put("timestamp", System.currentTimeMillis());
        return Mono.just(summary);
    }

    public record ConfigImportRequest(List<ConfigImportItem> configs) {}

    public record ConfigImportItem(String namespace, String group, String dataId,
                                   String content, String contentType) {}

    public record ServiceImportRequest(List<ServiceImportItem> services) {}

    public record ServiceImportItem(String namespace, String group, String serviceName,
                                    List<InstanceImportItem> instances) {}

    public record InstanceImportItem(String ip, int port, double weight,
                                     String clusterName, Map<String, String> metadata) {}
}
