package com.x.registry.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GrayRuleManager {

    private static final Logger log = LoggerFactory.getLogger(GrayRuleManager.class);

    private final Map<String, List<GrayRule>> rules = new ConcurrentHashMap<>();

    public void addRule(GrayRule rule) {
        String key = buildKey(rule.getNamespace(), rule.getGroup(), rule.getDataId());
        rules.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        rules.get(key).sort(Comparator.comparingInt(GrayRule::getPriority).reversed());
        log.info("Added gray rule for {}/{}/{}, type={}, targets={}",
                rule.getNamespace(), rule.getGroup(), rule.getDataId(), rule.getType(), rule.getTargets());
    }

    public void removeRule(String namespace, String group, String dataId) {
        String key = buildKey(namespace, group, dataId);
        rules.remove(key);
        log.info("Removed gray rules for {}/{}/{}", namespace, group, dataId);
    }

    public String resolveContent(String namespace, String group, String dataId,
                                 String clientIp, Set<String> clientLabels, String defaultContent) {
        String key = buildKey(namespace, group, dataId);
        List<GrayRule> ruleList = rules.get(key);
        if (ruleList == null || ruleList.isEmpty()) {
            return defaultContent;
        }

        for (GrayRule rule : ruleList) {
            if (rule.matches(clientIp, clientLabels)) {
                return rule.getGrayContent();
            }
        }
        return defaultContent;
    }

    public boolean hasGrayRules(String namespace, String group, String dataId) {
        String key = buildKey(namespace, group, dataId);
        List<GrayRule> ruleList = rules.get(key);
        return ruleList != null && !ruleList.isEmpty();
    }

    public List<GrayRule> getRules(String namespace, String group, String dataId) {
        String key = buildKey(namespace, group, dataId);
        return rules.getOrDefault(key, Collections.emptyList());
    }

    private String buildKey(String namespace, String group, String dataId) {
        return namespace + "@@" + group + "@@" + dataId;
    }
}
