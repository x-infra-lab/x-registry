package com.x.registry.server.http;

import com.x.registry.api.model.ConfigItem;
import com.x.registry.api.model.Instance;
import com.x.registry.server.audit.AuditEvent;
import com.x.registry.server.audit.AuditLogger;
import com.x.registry.server.cluster.ClusterManager;
import com.x.registry.server.config.ConfigManager;
import com.x.registry.server.naming.ServiceManager;
import com.x.registry.server.naming.SubscriberManager;
import com.x.registry.server.storage.ConfigStore;
import com.x.registry.server.storage.InstanceStore;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/console")
public class ConsoleController {

    private final InstanceStore instanceStore;
    private final ConfigStore configStore;
    private final ServiceManager serviceManager;
    private final ConfigManager configManager;
    private final SubscriberManager subscriberManager;
    private final ClusterManager clusterManager;
    private final AuditLogger auditLogger;

    public ConsoleController(InstanceStore instanceStore, ConfigStore configStore,
                             ServiceManager serviceManager, ConfigManager configManager,
                             SubscriberManager subscriberManager, ClusterManager clusterManager,
                             AuditLogger auditLogger) {
        this.instanceStore = instanceStore;
        this.configStore = configStore;
        this.serviceManager = serviceManager;
        this.configManager = configManager;
        this.subscriberManager = subscriberManager;
        this.clusterManager = clusterManager;
        this.auditLogger = auditLogger;
    }

    @GetMapping("/overview")
    public Mono<Map<String, Object>> overview() {
        List<String> serviceKeys = instanceStore.getAllServiceKeys();
        int totalInstances = 0;
        int healthyInstances = 0;

        for (String key : serviceKeys) {
            String[] parts = key.split("@@");
            if (parts.length == 3) {
                List<Instance> all = instanceStore.getInstances(parts[0], parts[1], parts[2], false);
                totalInstances += all.size();
                healthyInstances += (int) all.stream().filter(Instance::isHealthy).count();
            }
        }

        return Mono.just(Map.of(
                "serviceCount", serviceKeys.size(),
                "instanceCount", totalInstances,
                "healthyInstanceCount", healthyInstances,
                "subscriberCount", subscriberManager.getSubscriberCount(),
                "clusterMode", clusterManager.isClustered() ? "cluster" : "standalone",
                "memberCount", clusterManager.getMembers().size()
        ));
    }

    @GetMapping("/services")
    public Mono<Map<String, Object>> listServices(
            @RequestParam(defaultValue = "") String namespace,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        List<String> allKeys = instanceStore.getAllServiceKeys();

        List<Map<String, Object>> services = allKeys.stream()
                .filter(key -> {
                    if (!namespace.isEmpty() && !key.startsWith(namespace + "@@")) return false;
                    if (!keyword.isEmpty() && !key.contains(keyword)) return false;
                    return true;
                })
                .map(key -> {
                    String[] parts = key.split("@@");
                    String ns = parts[0];
                    String group = parts[1];
                    String name = parts[2];
                    List<Instance> instances = instanceStore.getInstances(ns, group, name, false);
                    long healthy = instances.stream().filter(Instance::isHealthy).count();
                    return Map.<String, Object>of(
                            "namespace", ns,
                            "group", group,
                            "serviceName", name,
                            "instanceCount", instances.size(),
                            "healthyCount", healthy
                    );
                })
                .collect(Collectors.toList());

        int total = services.size();
        int from = Math.min(page * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> pageData = services.subList(from, to);

        return Mono.just(Map.of(
                "services", pageData,
                "total", total,
                "page", page,
                "pageSize", pageSize
        ));
    }

    @GetMapping("/services/{namespace}/{group}/{serviceName}/instances")
    public Mono<Map<String, Object>> getServiceInstances(
            @PathVariable String namespace,
            @PathVariable String group,
            @PathVariable String serviceName) {
        List<Instance> instances = serviceManager.getInstances(namespace, serviceName, group, false);
        return Mono.just(Map.of(
                "serviceName", serviceName,
                "namespace", namespace,
                "group", group,
                "instances", instances,
                "total", instances.size()
        ));
    }

    @GetMapping("/configs")
    public Mono<Map<String, Object>> listConfigs(
            @RequestParam(defaultValue = "") String namespace,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        List<ConfigItem> allConfigs = configStore.listAll();

        List<Map<String, Object>> configs = allConfigs.stream()
                .filter(item -> {
                    if (!namespace.isEmpty() && !namespace.equals(item.getNamespace())) return false;
                    if (!keyword.isEmpty() && !item.getDataId().contains(keyword)) return false;
                    return true;
                })
                .map(item -> Map.<String, Object>of(
                        "namespace", item.getNamespace(),
                        "group", item.getGroup(),
                        "dataId", item.getDataId(),
                        "contentType", item.getContentType() != null ? item.getContentType() : "text",
                        "version", item.getVersion(),
                        "lastModified", item.getLastModified()
                ))
                .collect(Collectors.toList());

        int total = configs.size();
        int from = Math.min(page * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> pageData = configs.subList(from, to);

        return Mono.just(Map.of(
                "configs", pageData,
                "total", total,
                "page", page,
                "pageSize", pageSize
        ));
    }

    @GetMapping("/configs/{namespace}/{group}/{dataId}")
    public Mono<Map<String, Object>> getConfigDetail(
            @PathVariable String namespace,
            @PathVariable String group,
            @PathVariable String dataId) {
        ConfigItem item = configManager.getConfig(namespace, dataId, group);
        if (item == null) {
            return Mono.just(Map.of("found", false));
        }
        return Mono.just(Map.of(
                "found", true,
                "namespace", item.getNamespace(),
                "group", item.getGroup(),
                "dataId", item.getDataId(),
                "content", item.getContent() != null ? item.getContent() : "",
                "contentType", item.getContentType() != null ? item.getContentType() : "text",
                "md5", item.getMd5() != null ? item.getMd5() : "",
                "version", item.getVersion(),
                "lastModified", item.getLastModified()
        ));
    }

    @PostMapping("/configs/{namespace}/{group}/{dataId}/rollback")
    public Mono<Map<String, Object>> rollbackConfig(
            @PathVariable String namespace,
            @PathVariable String group,
            @PathVariable String dataId,
            @RequestParam long version) {
        ConfigItem item = configManager.rollback(namespace, dataId, group, version);
        if (item == null) {
            return Mono.just(Map.of("success", false, "message", "Version not found"));
        }
        return Mono.just(Map.of("success", true, "version", item.getVersion()));
    }

    @GetMapping("/audit")
    public Mono<Map<String, Object>> getAuditLog(
            @RequestParam(defaultValue = "50") int limit) {
        List<AuditEvent> events = auditLogger.getRecentEvents(Math.min(limit, 200));
        List<Map<String, Object>> items = events.stream()
                .map(e -> Map.<String, Object>of(
                        "timestamp", e.getTimestamp(),
                        "action", e.getAction().name(),
                        "namespace", e.getNamespace() != null ? e.getNamespace() : "",
                        "resource", e.getResource() != null ? e.getResource() : "",
                        "operator", e.getOperator() != null ? e.getOperator() : ""
                ))
                .collect(Collectors.toList());
        return Mono.just(Map.of("events", items, "total", items.size()));
    }
}
