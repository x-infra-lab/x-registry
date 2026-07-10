package com.x.registry.client;

public class XRegistryClientConfig {

    private String serverAddr = "127.0.0.1:9848";
    private String namespace = "public";
    private long connectTimeoutMs = 5000;
    private long requestTimeoutMs = 5000;
    private long heartbeatIntervalMs = 5000;
    private String cacheDir;
    private String authToken;
    private boolean tlsEnabled = false;
    private String certPath;
    private String keyPath;
    private String trustCertPath;

    public XRegistryClientConfig() {
        this.cacheDir = System.getProperty("user.home") + "/.x-registry/cache";
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public XRegistryClientConfig setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
        return this;
    }

    public String getNamespace() {
        return namespace;
    }

    public XRegistryClientConfig setNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public XRegistryClientConfig setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        return this;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public XRegistryClientConfig setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
        return this;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public XRegistryClientConfig setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        return this;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public XRegistryClientConfig setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
        return this;
    }

    public String getAuthToken() {
        return authToken;
    }

    public XRegistryClientConfig setAuthToken(String authToken) {
        this.authToken = authToken;
        return this;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public XRegistryClientConfig setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
        return this;
    }

    public String getCertPath() {
        return certPath;
    }

    public XRegistryClientConfig setCertPath(String certPath) {
        this.certPath = certPath;
        return this;
    }

    public String getKeyPath() {
        return keyPath;
    }

    public XRegistryClientConfig setKeyPath(String keyPath) {
        this.keyPath = keyPath;
        return this;
    }

    public String getTrustCertPath() {
        return trustCertPath;
    }

    public XRegistryClientConfig setTrustCertPath(String trustCertPath) {
        this.trustCertPath = trustCertPath;
        return this;
    }

    public String getHost() {
        return serverAddr.split(":")[0];
    }

    public int getPort() {
        String[] parts = serverAddr.split(":");
        return parts.length > 1 ? Integer.parseInt(parts[1]) : 9848;
    }
}
