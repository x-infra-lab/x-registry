package com.x.registry.server.http;

import com.x.registry.api.model.ConfigItem;
import com.x.registry.server.config.ConfigManager;
import com.x.registry.server.config.ConfigWatcherManager;
import com.x.registry.server.config.GrayRule;
import com.x.registry.server.config.GrayRuleManager;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@RestController
@RequestMapping("/v1/ns/{namespace}/config")
public class ConfigController {

    private static final Duration LONG_POLL_TIMEOUT = Duration.ofSeconds(30);

    private final ConfigManager configManager;
    private final ConfigWatcherManager watcherManager;
    private final GrayRuleManager grayRuleManager;

    public ConfigController(ConfigManager configManager, ConfigWatcherManager watcherManager,
                            GrayRuleManager grayRuleManager) {
        this.configManager = configManager;
        this.watcherManager = watcherManager;
        this.grayRuleManager = grayRuleManager;
    }

    @GetMapping
    public Mono<Map<String, Object>> getConfig(
            @PathVariable String namespace,
            @RequestParam String dataId,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group,
            ServerHttpRequest request) {
        ConfigItem item = configManager.getConfig(namespace, dataId, group);
        if (item == null) {
            return Mono.just(Map.of("found", false));
        }

        // Resolve gray rules against client IP
        String clientIp = extractClientIp(request);
        String content = grayRuleManager.resolveContent(
                namespace, group, dataId, clientIp, Collections.emptySet(), item.getContent());

        return Mono.just(Map.of(
                "found", true,
                "dataId", item.getDataId(),
                "group", item.getGroup(),
                "namespace", item.getNamespace(),
                "content", content,
                "contentType", item.getContentType(),
                "md5", item.getMd5(),
                "version", item.getVersion(),
                "lastModified", item.getLastModified()
        ));
    }

    private String extractClientIp(ServerHttpRequest request) {
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        InetSocketAddress remoteAddr = request.getRemoteAddress();
        return remoteAddr != null ? remoteAddr.getAddress().getHostAddress() : "";
    }

    @PostMapping
    public Mono<Map<String, Object>> publishConfig(
            @PathVariable String namespace,
            @RequestBody ConfigPublishRequest request) {
        String group = request.group() != null ? request.group() : "DEFAULT_GROUP";
        ConfigItem item = configManager.publishConfig(
                namespace, request.dataId(), group,
                request.content(), request.contentType(),
                request.operator(), request.description());
        return Mono.just(Map.of(
                "success", true,
                "version", item.getVersion(),
                "md5", item.getMd5()
        ));
    }

    @DeleteMapping
    public Mono<Map<String, Object>> deleteConfig(
            @PathVariable String namespace,
            @RequestParam String dataId,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group,
            @RequestParam(required = false) String operator) {
        boolean removed = configManager.removeConfig(namespace, dataId, group, operator);
        return Mono.just(Map.of("success", removed));
    }

    @GetMapping("/history")
    public Mono<Map<String, Object>> listHistory(
            @PathVariable String namespace,
            @RequestParam String dataId,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        List<ConfigItem> history = configManager.listHistory(namespace, dataId, group, page, pageSize);
        int total = configManager.getHistoryCount(namespace, dataId, group);
        return Mono.just(Map.of(
                "revisions", history,
                "total", total,
                "page", page,
                "pageSize", pageSize
        ));
    }

    @PostMapping("/listener")
    public Mono<Map<String, Object>> longPollListener(
            @PathVariable String namespace,
            @RequestBody ConfigListenRequest request) {
        String group = request.group() != null ? request.group() : "DEFAULT_GROUP";

        ConfigItem current = configManager.getConfig(namespace, request.dataId(), group);
        if (current != null && !current.getMd5().equals(request.currentMd5())) {
            return Mono.just(Map.of(
                    "changed", true,
                    "dataId", current.getDataId(),
                    "content", current.getContent(),
                    "md5", current.getMd5(),
                    "version", current.getVersion()
            ));
        }

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Consumer<ConfigItem> watcher = item -> {
            if (!future.isDone()) {
                future.complete(Map.of(
                        "changed", true,
                        "dataId", item.getDataId(),
                        "content", item.getContent() != null ? item.getContent() : "",
                        "md5", item.getMd5() != null ? item.getMd5() : "",
                        "version", item.getVersion()
                ));
            }
        };

        watcherManager.addWatcher(namespace, group, request.dataId(), watcher);

        return Mono.fromFuture(future)
                .timeout(LONG_POLL_TIMEOUT, Mono.just(Map.of("changed", false)))
                .doFinally(signal -> watcherManager.removeWatcher(namespace, group, request.dataId(), watcher));
    }

    @GetMapping("/diff")
    public Mono<Map<String, Object>> diffVersions(
            @PathVariable String namespace,
            @RequestParam String dataId,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group,
            @RequestParam long fromVersion,
            @RequestParam long toVersion) {
        ConfigItem from = configManager.getConfigVersion(namespace, dataId, group, fromVersion);
        ConfigItem to = configManager.getConfigVersion(namespace, dataId, group, toVersion);
        if (from == null || to == null) {
            return Mono.just(Map.of("found", false, "message", "One or both versions not found"));
        }
        List<String> diff = computeLineDiff(
                from.getContent() != null ? from.getContent() : "",
                to.getContent() != null ? to.getContent() : "");
        return Mono.just(Map.of(
                "found", true,
                "dataId", dataId,
                "fromVersion", fromVersion,
                "toVersion", toVersion,
                "diff", diff
        ));
    }

    private List<String> computeLineDiff(String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        List<String> result = new java.util.ArrayList<>();

        int maxLen = Math.max(oldLines.length, newLines.length);
        for (int i = 0; i < maxLen; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;
            if (oldLine == null) {
                result.add("+ " + newLine);
            } else if (newLine == null) {
                result.add("- " + oldLine);
            } else if (!oldLine.equals(newLine)) {
                result.add("- " + oldLine);
                result.add("+ " + newLine);
            } else {
                result.add("  " + oldLine);
            }
        }
        return result;
    }

    @PostMapping("/rollback")
    public Mono<Map<String, Object>> rollbackConfig(
            @PathVariable String namespace,
            @RequestBody ConfigRollbackRequest request) {
        String group = request.group() != null ? request.group() : "DEFAULT_GROUP";
        ConfigItem item = configManager.rollback(namespace, request.dataId(), group, request.targetVersion());
        if (item == null) {
            return Mono.just(Map.of("success", false, "message", "Target version not found"));
        }
        return Mono.just(Map.of(
                "success", true,
                "version", item.getVersion(),
                "md5", item.getMd5()
        ));
    }

    @PostMapping("/gray")
    public Mono<Map<String, Object>> publishGrayConfig(
            @PathVariable String namespace,
            @RequestBody GrayPublishRequest request) {
        String group = request.group() != null ? request.group() : "DEFAULT_GROUP";
        GrayRule rule = new GrayRule(
                namespace, group, request.dataId(),
                GrayRule.GrayType.valueOf(request.type()),
                Set.copyOf(request.targets()),
                request.content(),
                request.priority() != null ? request.priority() : 0
        );
        grayRuleManager.addRule(rule);
        return Mono.just(Map.of("success", true));
    }

    @DeleteMapping("/gray")
    public Mono<Map<String, Object>> deleteGrayConfig(
            @PathVariable String namespace,
            @RequestParam String dataId,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group) {
        grayRuleManager.removeRule(namespace, group, dataId);
        return Mono.just(Map.of("success", true));
    }

    @PostMapping("/gray/promote")
    public Mono<Map<String, Object>> promoteGrayConfig(
            @PathVariable String namespace,
            @RequestBody GrayPromoteRequest request) {
        String group = request.group() != null ? request.group() : "DEFAULT_GROUP";
        List<GrayRule> rules = grayRuleManager.getRules(namespace, group, request.dataId());
        if (rules.isEmpty()) {
            return Mono.just(Map.of("success", false, "message", "No gray rules found"));
        }
        String grayContent = rules.get(0).getGrayContent();
        ConfigItem item = configManager.publishConfig(
                namespace, request.dataId(), group,
                grayContent, null, request.operator(), "Promoted from gray release");
        grayRuleManager.removeRule(namespace, group, request.dataId());
        return Mono.just(Map.of("success", true, "version", item.getVersion()));
    }

    public record ConfigPublishRequest(
            String dataId,
            String group,
            String content,
            String contentType,
            String operator,
            String description
    ) {}

    public record ConfigListenRequest(
            String dataId,
            String group,
            String currentMd5
    ) {}

    public record ConfigRollbackRequest(
            String dataId,
            String group,
            long targetVersion
    ) {}

    public record GrayPublishRequest(
            String dataId,
            String group,
            String content,
            String type,
            List<String> targets,
            Integer priority
    ) {}

    public record GrayPromoteRequest(
            String dataId,
            String group,
            String operator
    ) {}
}
