package com.x.registry.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "x-registry")
public class XRegistryProperties {

    private String serverAddr = "127.0.0.1:9848";
    private String namespace = "public";
    private String group = "DEFAULT_GROUP";
    private Discovery discovery = new Discovery();
    private Config config = new Config();

    public String getServerAddr() { return serverAddr; }
    public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public Discovery getDiscovery() { return discovery; }
    public void setDiscovery(Discovery discovery) { this.discovery = discovery; }
    public Config getConfig() { return config; }
    public void setConfig(Config config) { this.config = config; }

    public static class Discovery {
        private boolean enabled = true;
        private boolean register = true;
        private String serviceName;
        private String ip;
        private int port = -1;
        private double weight = 1.0;
        private boolean ephemeral = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isRegister() { return register; }
        public void setRegister(boolean register) { this.register = register; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }
        public boolean isEphemeral() { return ephemeral; }
        public void setEphemeral(boolean ephemeral) { this.ephemeral = ephemeral; }
    }

    public static class Config {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
