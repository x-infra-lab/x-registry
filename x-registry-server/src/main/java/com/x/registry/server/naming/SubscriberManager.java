package com.x.registry.server.naming;

import com.x.registry.api.model.Instance;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
public class SubscriberManager {

    private static final Logger log = LoggerFactory.getLogger(SubscriberManager.class);
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30000;

    private final Map<String, Set<Consumer<List<Instance>>>> subscribers = new ConcurrentHashMap<>();
    private final Counter pushCounter;
    private final Counter pushFailCounter;
    private final Timer pushTimer;
    private final ScheduledExecutorService retryExecutor;

    public SubscriberManager(MeterRegistry registry) {
        this.pushCounter = Counter.builder("x_registry_push_total")
                .description("Total service push notifications")
                .register(registry);
        this.pushFailCounter = Counter.builder("x_registry_push_fail_total")
                .description("Total failed push notifications")
                .register(registry);
        this.pushTimer = Timer.builder("x_registry_push_latency_seconds")
                .description("Push notification latency")
                .register(registry);
        this.retryExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "push-retry");
            t.setDaemon(true);
            return t;
        });
    }

    public void subscribe(String namespace, String group, String serviceName, Consumer<List<Instance>> listener) {
        String key = buildKey(namespace, group, serviceName);
        subscribers.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>()).add(listener);
        log.debug("New subscriber for service {}, total: {}", serviceName, subscribers.get(key).size());
    }

    public void unsubscribe(String namespace, String group, String serviceName, Consumer<List<Instance>> listener) {
        String key = buildKey(namespace, group, serviceName);
        Set<Consumer<List<Instance>>> listeners = subscribers.get(key);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    public void notify(String namespace, String group, String serviceName, List<Instance> instances) {
        String key = buildKey(namespace, group, serviceName);
        Set<Consumer<List<Instance>>> listeners = subscribers.get(key);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }

        pushTimer.record(() -> {
            for (Consumer<List<Instance>> listener : listeners) {
                try {
                    listener.accept(instances);
                    pushCounter.increment();
                } catch (Exception e) {
                    log.warn("Push failed for service {}, scheduling retry", serviceName, e);
                    pushFailCounter.increment();
                    scheduleRetry(listener, instances, serviceName, 1);
                }
            }
        });
    }

    private void scheduleRetry(Consumer<List<Instance>> listener, List<Instance> instances,
                               String serviceName, int attempt) {
        if (attempt > MAX_RETRY_ATTEMPTS) {
            log.error("Push retry exhausted for service {} after {} attempts", serviceName, MAX_RETRY_ATTEMPTS);
            return;
        }

        long delay = Math.min(BASE_RETRY_DELAY_MS * (1L << (attempt - 1)), MAX_RETRY_DELAY_MS);
        long jitter = ThreadLocalRandom.current().nextLong(delay / 4);

        retryExecutor.schedule(() -> {
            try {
                listener.accept(instances);
                pushCounter.increment();
                log.info("Push retry succeeded for service {} on attempt {}", serviceName, attempt);
            } catch (Exception e) {
                log.warn("Push retry {} failed for service {}", attempt, serviceName);
                pushFailCounter.increment();
                scheduleRetry(listener, instances, serviceName, attempt + 1);
            }
        }, delay + jitter, TimeUnit.MILLISECONDS);
    }

    public int getSubscriberCount(String namespace, String group, String serviceName) {
        String key = buildKey(namespace, group, serviceName);
        Set<Consumer<List<Instance>>> listeners = subscribers.get(key);
        return listeners != null ? listeners.size() : 0;
    }

    public int getSubscriberCount() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }

    private String buildKey(String namespace, String group, String serviceName) {
        return namespace + "@@" + group + "@@" + serviceName;
    }
}
