package com.x.registry.api.model;

import java.io.Serializable;

public class Namespace implements Serializable {

    public static final String DEFAULT = "public";

    private String namespaceId;
    private String name;
    private String description;

    public Namespace() {
    }

    public Namespace(String namespaceId, String name) {
        this.namespaceId = namespaceId;
        this.name = name;
    }

    public String getNamespaceId() {
        return namespaceId;
    }

    public void setNamespaceId(String namespaceId) {
        this.namespaceId = namespaceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
