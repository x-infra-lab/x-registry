package com.x.registry.server.security;

import java.util.Set;

public class AuthToken {

    private String id;
    private String secret;
    private Set<String> namespaces;
    private Set<Permission> permissions;

    public AuthToken() {
    }

    public AuthToken(String id, String secret, Set<String> namespaces, Set<Permission> permissions) {
        this.id = id;
        this.secret = secret;
        this.namespaces = namespaces;
        this.permissions = permissions;
    }

    public boolean hasPermission(Permission permission) {
        return permissions != null && permissions.contains(permission);
    }

    public boolean hasNamespaceAccess(String namespace) {
        return namespaces != null && (namespaces.contains("*") || namespaces.contains(namespace));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Set<String> getNamespaces() {
        return namespaces;
    }

    public void setNamespaces(Set<String> namespaces) {
        this.namespaces = namespaces;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions;
    }
}
