# X-Registry

A unified service registry and configuration center for microservices, built with Java 17+ and Spring Boot.

## Features

- **Service Discovery** - Register, deregister, and query service instances with health checking
- **Configuration Management** - Publish, version, and watch configuration changes in real-time
- **Cluster Support** - Gossip-based member discovery, Distro AP protocol for instance data, Raft CP protocol for config data
- **Push Notifications** - Real-time push of service changes to subscribers with 100ms aggregation window
- **Gray Release** - Canary config deployment by IP or label matching
- **Pluggable Storage** - In-memory (default) or RocksDB persistent storage
- **Security** - Token-based authentication with RBAC (optional)
- **Observability** - Micrometer metrics with Prometheus endpoint
- **Management Console** - Built-in Web UI for service/config management
- **Spring Boot Starter** - Auto-registration and discovery integration

## Architecture

```
Client SDK (gRPC)  ──┐
                     ├──> X-Registry Server
HTTP API           ──┘       ├── Naming Module (Service Registry)
                             ├── Config Module (Configuration Center)
                             ├── Cluster (Gossip + Distro + Raft)
                             └── Storage (Memory / RocksDB)
```

## Quick Start

### 1. Build

```bash
mvn clean package -DskipTests
```

### 2. Start Server

```bash
java -jar x-registry-server/target/x-registry-server-1.0.0-SNAPSHOT.jar
```

The server starts on:
- HTTP API: `http://localhost:8848`
- gRPC: `localhost:9848`
- Console: `http://localhost:8848/console`

### 3. Register a Service (HTTP)

```bash
curl -X POST http://localhost:8848/v1/ns/public/naming/instance \
  -H "Content-Type: application/json" \
  -d '{
    "serviceName": "my-service",
    "ip": "10.0.0.1",
    "port": 8080,
    "weight": 1.0,
    "ephemeral": true
  }'
```

### 4. Query Instances

```bash
curl "http://localhost:8848/v1/ns/public/naming/instances?serviceName=my-service"
```

### 5. Publish Configuration

```bash
curl -X POST http://localhost:8848/v1/ns/public/config \
  -H "Content-Type: application/json" \
  -d '{
    "dataId": "app.yaml",
    "content": "server:\n  port: 8080",
    "contentType": "yaml"
  }'
```

### 6. Get Configuration

```bash
curl "http://localhost:8848/v1/ns/public/config?dataId=app.yaml"
```

## Java Client SDK

```java
XRegistryClient client = XRegistryClient.create("127.0.0.1:9848");

// Register instance
Instance instance = new Instance();
instance.setIp("10.0.0.1");
instance.setPort(8080);
instance.setEphemeral(true);
client.getNamingService().registerInstance("public", "my-service", "DEFAULT_GROUP", instance);

// Subscribe to changes
client.getNamingService().subscribe("public", "my-service", "DEFAULT_GROUP", instances -> {
    System.out.println("Instances updated: " + instances.size());
});

// Publish config
client.getConfigService().publishConfig("public", "app.yaml", "DEFAULT_GROUP",
    "key: value", "yaml", "operator", null);

// Watch config
client.getConfigService().addListener("public", "app.yaml", "DEFAULT_GROUP", item -> {
    System.out.println("Config changed: " + item.getContent());
});
```

## Spring Boot Integration

Add the starter dependency:

```xml
<dependency>
    <groupId>com.x.registry</groupId>
    <artifactId>x-registry-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Configure in `application.yaml`:

```yaml
x-registry:
  server-addr: 127.0.0.1:9848
  namespace: public
  group: DEFAULT_GROUP
  discovery:
    enabled: true
    register: true
    service-name: ${spring.application.name}
    port: ${server.port}
```

Use the discovery client:

```java
@Autowired
private XRegistryDiscoveryClient discoveryClient;

public List<Instance> getServiceInstances() {
    return discoveryClient.getInstances("target-service");
}
```

## Configuration

### Server Configuration (`application.yaml`)

```yaml
server:
  port: 8848

x-registry:
  grpc:
    port: 9848
  health-check:
    interval-ms: 5000
    unhealthy-threshold-ms: 15000
    remove-threshold-ms: 30000
  cluster:
    enabled: false
    bind-address: 127.0.0.1
    gossip-port: 7848
    seed-nodes: []
  auth:
    enabled: false
    tokens:
      - id: admin
        secret: "your-secret"
        namespaces: ["*"]
        permissions: [READ, WRITE, ADMIN]
  storage:
    type: memory  # memory | rocksdb
    rocksdb:
      path: ./data/rocksdb
```

### Cluster Deployment

For a 3-node cluster:

```yaml
# Node 1
x-registry:
  cluster:
    enabled: true
    bind-address: 192.168.1.1
    seed-nodes: [192.168.1.2:7848, 192.168.1.3:7848]

# Node 2
x-registry:
  cluster:
    enabled: true
    bind-address: 192.168.1.2
    seed-nodes: [192.168.1.1:7848, 192.168.1.3:7848]

# Node 3
x-registry:
  cluster:
    enabled: true
    bind-address: 192.168.1.3
    seed-nodes: [192.168.1.1:7848, 192.168.1.2:7848]
```

## API Reference

### Naming API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/v1/ns/{ns}/naming/instance` | Register instance |
| DELETE | `/v1/ns/{ns}/naming/instance` | Deregister instance |
| GET | `/v1/ns/{ns}/naming/instances` | Query instances |
| PUT | `/v1/ns/{ns}/naming/instance/beat` | Send heartbeat |

### Config API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/ns/{ns}/config` | Get config |
| POST | `/v1/ns/{ns}/config` | Publish config |
| DELETE | `/v1/ns/{ns}/config` | Delete config |
| GET | `/v1/ns/{ns}/config/history` | List history |
| POST | `/v1/ns/{ns}/config/gray` | Publish gray config |
| POST | `/v1/ns/{ns}/config/gray/promote` | Promote gray release |

### Ops API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/ops/export/configs` | Export all configs |
| POST | `/v1/ops/import/configs` | Import configs |
| GET | `/v1/ops/export/services` | Export persistent services |
| POST | `/v1/ops/import/services` | Import services |

### Cluster API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/cluster/health` | Cluster health |
| GET | `/v1/cluster/members` | List members |
| GET | `/v1/cluster/leader` | Current leader |

## Modules

| Module | Description |
|--------|-------------|
| `x-registry-api` | API models, interfaces, protobuf definitions |
| `x-registry-server` | Server implementation |
| `x-registry-client` | Java client SDK (gRPC-based) |
| `x-registry-spring-boot-starter` | Spring Boot auto-configuration |
| `x-registry-benchmark` | JMH performance benchmarks |

## Benchmarks

```bash
java -jar x-registry-benchmark/target/benchmarks.jar
```

Available benchmarks:
- `RegisterBenchmark` - Instance registration throughput
- `DiscoveryBenchmark` - Instance query throughput
- `ConfigPublishBenchmark` - Config publish throughput
- `PushLatencyBenchmark` - End-to-end push latency

## License

[Apache License 2.0](LICENSE)
