package com.x.registry.server.storage;

import com.x.registry.api.model.Namespace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NamespaceStore {

    private final Map<String, Namespace> namespaces = new ConcurrentHashMap<>();

    public NamespaceStore() {
        Namespace defaultNs = new Namespace("public", "Public");
        defaultNs.setDescription("Default public namespace");
        namespaces.put("public", defaultNs);
    }

    public Namespace get(String namespaceId) {
        return namespaces.get(namespaceId);
    }

    public List<Namespace> listAll() {
        return new ArrayList<>(namespaces.values());
    }

    public Namespace create(String namespaceId, String name, String description) {
        if (namespaces.containsKey(namespaceId)) {
            return null;
        }
        Namespace ns = new Namespace(namespaceId, name);
        ns.setDescription(description);
        namespaces.put(namespaceId, ns);
        return ns;
    }

    public boolean update(String namespaceId, String name, String description) {
        Namespace ns = namespaces.get(namespaceId);
        if (ns == null) return false;
        if (name != null) ns.setName(name);
        if (description != null) ns.setDescription(description);
        return true;
    }

    public boolean delete(String namespaceId) {
        if ("public".equals(namespaceId)) return false;
        return namespaces.remove(namespaceId) != null;
    }

    public boolean exists(String namespaceId) {
        return namespaces.containsKey(namespaceId);
    }
}
