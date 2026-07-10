package com.x.registry.client.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public EventBus() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "x-registry-eventbus");
            t.setDaemon(true);
            return t;
        });
    }

    @SuppressWarnings("unchecked")
    public <T> void subscribe(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    public <T> void unsubscribe(Class<T> eventType, Consumer<T> listener) {
        List<Consumer<?>> list = listeners.get(eventType);
        if (list != null) {
            list.remove(listener);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void publish(T event) {
        List<Consumer<?>> list = listeners.get(event.getClass());
        if (list == null || list.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            for (Consumer<?> listener : list) {
                try {
                    ((Consumer<T>) listener).accept(event);
                } catch (Exception e) {
                    log.warn("EventBus listener error for {}: {}", event.getClass().getSimpleName(), e.getMessage());
                }
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
        listeners.clear();
    }
}
