package com.x.registry.server.http;

import com.x.registry.api.model.Instance;
import com.x.registry.server.naming.ServiceManager;
import com.x.registry.server.naming.SubscriberManager;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@RestController
@RequestMapping("/v1/ns/{namespace}/naming")
public class NamingController {

    private static final Duration LONG_POLL_TIMEOUT = Duration.ofSeconds(30);

    private final ServiceManager serviceManager;
    private final SubscriberManager subscriberManager;

    public NamingController(ServiceManager serviceManager, SubscriberManager subscriberManager) {
        this.serviceManager = serviceManager;
        this.subscriberManager = subscriberManager;
    }

    @PostMapping("/instance")
    public Mono<Map<String, Object>> registerInstance(
            @PathVariable String namespace,
            @RequestBody InstanceRequest request) {
        Instance instance = new Instance();
        instance.setIp(request.ip());
        instance.setPort(request.port());
        instance.setWeight(request.weight() > 0 ? request.weight() : 1.0);
        instance.setClusterName(request.clusterName() != null ? request.clusterName() : "DEFAULT");
        instance.setEphemeral(request.ephemeral() == null || request.ephemeral());
        instance.setEnabled(request.enabled() == null || request.enabled());
        instance.setMetadata(request.metadata());

        String group = request.group() != null ? request.group() : "DEFAULT_GROUP";
        serviceManager.registerInstance(namespace, request.serviceName(), group, instance);

        return Mono.just(Map.of("success", true));
    }

    @DeleteMapping("/instance")
    public Mono<Map<String, Object>> deregisterInstance(
            @PathVariable String namespace,
            @RequestParam String serviceName,
            @RequestParam String ip,
            @RequestParam int port,
            @RequestParam(defaultValue = "DEFAULT") String clusterName,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setClusterName(clusterName);
        instance.setServiceName(serviceName);

        serviceManager.deregisterInstance(namespace, serviceName, group, instance);
        return Mono.just(Map.of("success", true));
    }

    @GetMapping("/instances")
    public Mono<Map<String, Object>> getInstances(
            @PathVariable String namespace,
            @RequestParam String serviceName,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group,
            @RequestParam(defaultValue = "false") boolean healthyOnly) {
        List<Instance> instances = serviceManager.getInstances(namespace, serviceName, group, healthyOnly);
        return Mono.just(Map.of(
                "serviceName", serviceName,
                "instances", instances,
                "count", instances.size()
        ));
    }

    @PutMapping("/instance/beat")
    public Mono<Map<String, Object>> heartbeat(
            @PathVariable String namespace,
            @RequestParam String serviceName,
            @RequestParam String ip,
            @RequestParam int port,
            @RequestParam(defaultValue = "DEFAULT") String clusterName,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group) {
        boolean success = serviceManager.processHeartbeat(namespace, serviceName, group, ip, port, clusterName);
        return Mono.just(Map.of(
                "success", success,
                "nextIntervalMs", 5000
        ));
    }

    @GetMapping("/subscribe")
    public Mono<Map<String, Object>> subscribe(
            @PathVariable String namespace,
            @RequestParam String serviceName,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        Consumer<List<Instance>> listener = instances -> {
            if (!future.isDone()) {
                future.complete(Map.of(
                        "changed", true,
                        "serviceName", serviceName,
                        "instances", instances,
                        "count", instances.size()
                ));
            }
        };

        subscriberManager.subscribe(namespace, group, serviceName, listener);

        return Mono.fromFuture(future)
                .timeout(LONG_POLL_TIMEOUT, Mono.just(Map.of("changed", false)))
                .doFinally(signal -> subscriberManager.unsubscribe(namespace, group, serviceName, listener));
    }

    public record InstanceRequest(
            String serviceName,
            String group,
            String ip,
            int port,
            double weight,
            String clusterName,
            Boolean ephemeral,
            Boolean enabled,
            Map<String, String> metadata
    ) {}
}
