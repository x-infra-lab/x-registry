package com.x.registry.server.http;

import com.x.registry.api.model.Namespace;
import com.x.registry.server.storage.NamespaceStore;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/namespaces")
public class NamespaceController {

    private final NamespaceStore namespaceStore;

    public NamespaceController(NamespaceStore namespaceStore) {
        this.namespaceStore = namespaceStore;
    }

    @GetMapping
    public Mono<Map<String, Object>> listNamespaces() {
        List<Namespace> namespaces = namespaceStore.listAll();
        return Mono.just(Map.of("namespaces", namespaces, "total", namespaces.size()));
    }

    @GetMapping("/{namespaceId}")
    public Mono<Map<String, Object>> getNamespace(@PathVariable String namespaceId) {
        Namespace ns = namespaceStore.get(namespaceId);
        if (ns == null) {
            return Mono.just(Map.of("found", false));
        }
        return Mono.just(Map.of(
                "found", true,
                "namespaceId", ns.getNamespaceId(),
                "name", ns.getName(),
                "description", ns.getDescription() != null ? ns.getDescription() : ""
        ));
    }

    @PostMapping
    public Mono<Map<String, Object>> createNamespace(@RequestBody NamespaceRequest request) {
        Namespace ns = namespaceStore.create(request.namespaceId(), request.name(), request.description());
        if (ns == null) {
            return Mono.just(Map.of("success", false, "message", "Namespace already exists"));
        }
        return Mono.just(Map.of("success", true, "namespaceId", ns.getNamespaceId()));
    }

    @PutMapping("/{namespaceId}")
    public Mono<Map<String, Object>> updateNamespace(
            @PathVariable String namespaceId,
            @RequestBody NamespaceRequest request) {
        boolean updated = namespaceStore.update(namespaceId, request.name(), request.description());
        return Mono.just(Map.of("success", updated));
    }

    @DeleteMapping("/{namespaceId}")
    public Mono<Map<String, Object>> deleteNamespace(@PathVariable String namespaceId) {
        boolean deleted = namespaceStore.delete(namespaceId);
        if (!deleted) {
            return Mono.just(Map.of("success", false, "message", "Cannot delete default or non-existent namespace"));
        }
        return Mono.just(Map.of("success", true));
    }

    public record NamespaceRequest(String namespaceId, String name, String description) {}
}
