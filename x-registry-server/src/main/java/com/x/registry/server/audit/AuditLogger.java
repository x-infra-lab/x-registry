package com.x.registry.server.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");
    private static final int MAX_HISTORY = 1000;
    private static final int MAX_DETAIL_LENGTH = 200;

    private final LinkedList<AuditEvent> events = new LinkedList<>();
    private final List<Pattern> sensitivePatterns;

    public AuditLogger(@Value("${x-registry.audit.sensitive-patterns:password,secret,token,key,credential}") String patterns) {
        this.sensitivePatterns = Arrays.stream(patterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Pattern.compile("(?i)" + Pattern.quote(s)))
                .toList();
    }

    public void log(AuditEvent event) {
        log.info("{}", event);
        synchronized (events) {
            events.addFirst(event);
            while (events.size() > MAX_HISTORY) {
                events.removeLast();
            }
        }
    }

    public void logConfigPublish(String namespace, String dataId, String group, String operator) {
        log(new AuditEvent(AuditEvent.Action.CONFIG_PUBLISH, namespace,
                group + "/" + dataId, operator, null));
    }

    public void logConfigDelete(String namespace, String dataId, String group, String operator) {
        log(new AuditEvent(AuditEvent.Action.CONFIG_DELETE, namespace,
                group + "/" + dataId, operator, null));
    }

    public void logConfigRollback(String namespace, String dataId, String group, long targetVersion) {
        log(new AuditEvent(AuditEvent.Action.CONFIG_ROLLBACK, namespace,
                group + "/" + dataId, "system", "rollback to version " + targetVersion));
    }

    public void logGrayPublish(String namespace, String dataId, String group) {
        log(new AuditEvent(AuditEvent.Action.CONFIG_GRAY_PUBLISH, namespace,
                group + "/" + dataId, "system", null));
    }

    public void logGrayPromote(String namespace, String dataId, String group, String operator) {
        log(new AuditEvent(AuditEvent.Action.CONFIG_GRAY_PROMOTE, namespace,
                group + "/" + dataId, operator, null));
    }

    public void logAuthFailure(String resource, String detail) {
        log(new AuditEvent(AuditEvent.Action.AUTH_FAILURE, "", resource, "anonymous", detail));
    }

    public List<AuditEvent> getRecentEvents(int limit) {
        synchronized (events) {
            int count = Math.min(limit, events.size());
            return new ArrayList<>(events.subList(0, count));
        }
    }

    public String sanitize(String content) {
        if (content == null) return null;
        String sanitized = content;
        for (Pattern pattern : sensitivePatterns) {
            sanitized = pattern.matcher(sanitized).replaceAll("***");
        }
        if (sanitized.length() > MAX_DETAIL_LENGTH) {
            sanitized = sanitized.substring(0, MAX_DETAIL_LENGTH) + "...[truncated]";
        }
        return sanitized;
    }
}
