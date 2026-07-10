# X-Registry 部署指南

## 前置条件

- JDK 17+
- Maven 3.8+
- (可选) Docker 用于容器化部署

## 1. 单节点部署

### 1.1 构建

```bash
mvn clean package -DskipTests
```

### 1.2 启动

```bash
java -jar x-registry-server/target/x-registry-server-1.0.0-SNAPSHOT.jar
```

默认端口：
- HTTP API: 8848
- gRPC: 9848
- Console: http://localhost:8848/console

### 1.3 验证

```bash
# 健康检查
curl http://localhost:8848/actuator/health

# 注册一个实例
curl -X POST http://localhost:8848/v1/ns/public/naming/instance \
  -H "Content-Type: application/json" \
  -d '{"serviceName":"test","ip":"10.0.0.1","port":8080,"ephemeral":true}'

# 查询实例
curl "http://localhost:8848/v1/ns/public/naming/instances?serviceName=test"
```

---

## 2. 集群部署 (3 节点)

### 2.1 节点配置

每个节点创建 `application-cluster.yaml`：

**Node 1 (192.168.1.1):**
```yaml
x-registry:
  cluster:
    enabled: true
    bind-address: 192.168.1.1
    gossip-port: 7848
    distro-port: 7849
    raft-port: 7850
    seed-nodes:
      - 192.168.1.2:7848
      - 192.168.1.3:7848
    raft-data-dir: /data/x-registry/raft
```

**Node 2 (192.168.1.2):**
```yaml
x-registry:
  cluster:
    enabled: true
    bind-address: 192.168.1.2
    gossip-port: 7848
    distro-port: 7849
    raft-port: 7850
    seed-nodes:
      - 192.168.1.1:7848
      - 192.168.1.3:7848
    raft-data-dir: /data/x-registry/raft
```

**Node 3 (192.168.1.3):**
```yaml
x-registry:
  cluster:
    enabled: true
    bind-address: 192.168.1.3
    gossip-port: 7848
    distro-port: 7849
    raft-port: 7850
    seed-nodes:
      - 192.168.1.1:7848
      - 192.168.1.2:7848
    raft-data-dir: /data/x-registry/raft
```

### 2.2 启动集群

```bash
java -jar x-registry-server.jar --spring.profiles.active=cluster
```

### 2.3 验证集群

```bash
# 查看成员
curl http://192.168.1.1:8848/v1/cluster/members

# 查看 Leader
curl http://192.168.1.1:8848/v1/cluster/leader

# 查看 Raft 状态
curl http://192.168.1.1:8848/v1/cluster/raft/status
```

### 2.4 集群端口说明

| 端口 | 用途 | 协议 |
|------|------|------|
| 8848 | HTTP API + Console | HTTP |
| 9848 | 客户端 gRPC | gRPC |
| 7848 | Gossip 成员发现 | gRPC |
| 7849 | Distro 数据同步 | gRPC |
| 7850 | Raft 一致性协议 | gRPC (Apache Ratis) |

---

## 3. Docker 部署

### 3.1 构建镜像

```bash
# 先构建 JAR
mvn clean package -DskipTests

# 构建 Docker 镜像
docker build -t x-registry .
```

### 3.2 单节点 Docker

```bash
docker run -d \
  --name x-registry \
  -p 8848:8848 \
  -p 9848:9848 \
  x-registry
```

### 3.3 3 节点集群 (docker-compose)

```bash
docker-compose up -d
```

docker-compose 会启动 3 个节点 (node1/node2/node3)，自动配置集群：

```bash
# 验证集群健康
curl http://localhost:8848/v1/cluster/health

# 查看所有成员
curl http://localhost:8848/v1/cluster/members
```

**访问端口映射：**

| 节点 | HTTP | gRPC |
|------|------|------|
| node1 | 8848 | 9848 |
| node2 | 8849 | 9849 |
| node3 | 8850 | 9850 |

---

## 4. 存储配置

### 4.1 RocksDB (默认，推荐生产使用)

```yaml
x-registry:
  storage:
    type: rocksdb
    rocksdb:
      path: /data/x-registry/rocksdb
    max-instance-count: 100000
```

### 4.2 内存存储 (测试/开发)

```yaml
x-registry:
  storage:
    type: memory
    max-instance-count: 100000
```

### 4.3 实例容量限制

`max-instance-count` 控制系统最大实例数（默认 100,000）。超限时注册请求会返回错误。根据服务器内存调整：

| 实例数 | 估算内存 |
|--------|----------|
| 100,000 | ~1 GB |
| 500,000 | ~5 GB |
| 1,000,000 | ~10 GB |

---

## 5. TLS 配置

### 5.1 客户端到服务端 TLS

```yaml
x-registry:
  grpc:
    tls:
      enabled: true
      cert-path: /path/to/server.crt
      key-path: /path/to/server.key
      trust-cert-path: /path/to/ca.crt   # 可选，启用 mTLS
```

### 5.2 集群节点间 TLS

```yaml
x-registry:
  cluster:
    tls:
      enabled: true
      cert-path: /path/to/node.crt
      key-path: /path/to/node.key
      trust-cert-path: /path/to/ca.crt
```

启用集群 TLS 后，Gossip、Distro 和 Ratis 之间的通信均使用 TLS 加密。

### 5.3 客户端 SDK TLS 配置

```java
XRegistryClientConfig config = new XRegistryClientConfig();
config.setServerAddr("registry.example.com:9848");
config.setTlsEnabled(true);
config.setCertPath("/path/to/client.crt");
config.setKeyPath("/path/to/client.key");
config.setTrustCertPath("/path/to/ca.crt");
```

### 5.4 证书生成参考

```bash
# 生成 CA
openssl req -x509 -newkey rsa:4096 -keyout ca.key -out ca.crt -days 365 -nodes

# 生成服务端证书
openssl req -newkey rsa:4096 -keyout server.key -out server.csr -nodes
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 365

# 生成客户端证书 (mTLS)
openssl req -newkey rsa:4096 -keyout client.key -out client.csr -nodes
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt -days 365
```

---

## 6. 认证配置

### 6.1 启用 Token 认证

```yaml
x-registry:
  auth:
    enabled: true
    tokens:
      - id: admin
        secret: "${ADMIN_TOKEN}"
        namespaces: ["*"]
        permissions: [READ, WRITE, ADMIN]
      - id: app-reader
        secret: "${APP_READER_TOKEN}"
        namespaces: ["production"]
        permissions: [READ]
      - id: app-writer
        secret: "${APP_WRITER_TOKEN}"
        namespaces: ["production"]
        permissions: [READ, WRITE]
```

### 6.2 客户端认证

**HTTP 请求：**
```bash
curl -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  http://localhost:8848/v1/ns/public/config?dataId=app.yaml
```

**Java SDK：**
```java
XRegistryClientConfig config = new XRegistryClientConfig();
config.setAuthToken("your-token");
```

### 6.3 Token 加密存储 (可选)

配置加密密钥后，Token 密钥将使用 AES-256-GCM 加密：

```yaml
x-registry:
  auth:
    encryption-key: "your-32-byte-base64-key"
```

---

## 7. 速率限制

### 7.1 gRPC 速率限制

基于客户端 IP 的滑动窗口速率限制，默认 1000 请求/秒/IP：

```yaml
x-registry:
  grpc:
    rate-limit:
      requests-per-second: 1000
```

超限请求返回 gRPC `RESOURCE_EXHAUSTED` 状态码。

### 7.2 调整建议

| 场景 | 建议值 |
|------|--------|
| 开发/测试 | 10000 (宽松) |
| 一般生产 | 1000 (默认) |
| 高并发生产 | 5000 |
| 严格限流 | 100 |

---

## 8. 健康检查

### 8.1 服务端健康检查配置

```yaml
x-registry:
  health-check:
    interval-ms: 5000        # 检查间隔
    unhealthy-threshold-ms: 15000  # 标记不健康阈值
    remove-threshold-ms: 30000     # 移除实例阈值
```

### 8.2 客户端请求超时

客户端 SDK 为所有 gRPC 调用设置 deadline（默认 5000ms）：

```java
XRegistryClientConfig config = new XRegistryClientConfig();
config.setRequestTimeoutMs(5000);  // 默认 5 秒
```

---

## 9. 监控

### 9.1 Prometheus 指标

指标通过 `/actuator/prometheus` 暴露：

```bash
curl http://localhost:8848/actuator/prometheus
```

**关键指标：**

| 指标 | 说明 |
|------|------|
| `x_registry_instance_register_total` | 实例注册计数 |
| `x_registry_instance_deregister_total` | 实例注销计数 |
| `x_registry_config_publish_total` | 配置发布计数 |
| `x_registry_push_total` | 推送计数 |
| `x_registry_push_latency_seconds` | 推送延迟 |

### 9.2 运维 API

```bash
# 指标摘要
curl http://localhost:8848/v1/ops/metrics/summary

# 活跃连接列表
curl http://localhost:8848/v1/ops/connections
```

### 9.3 Grafana 接入

配置 Prometheus 采集 `/actuator/prometheus` 端点，然后在 Grafana 中创建仪表盘监控：
- 实例注册/注销速率
- 配置发布速率
- 推送延迟 P99
- gRPC 连接数
- Raft 状态 (term, role)

---

## 10. JVM 调优

### 10.1 生产推荐

```bash
java -Xms512m -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/data/x-registry/heapdump.hprof \
  -jar x-registry-server.jar
```

### 10.2 大规模部署 (50K+ 连接)

```bash
java -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:+ParallelRefProcEnabled \
  -Dio.netty.leakDetection.level=disabled \
  -jar x-registry-server.jar
```

### 10.3 OS 级调优

```bash
# /etc/sysctl.conf
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.ip_local_port_range = 1024 65535
fs.file-max = 1000000

# /etc/security/limits.conf
* soft nofile 1000000
* hard nofile 1000000
```

---

## 11. 数据迁移

### 11.1 导出

```bash
# 导出配置
curl http://source:8848/v1/ops/export/configs > configs-backup.json

# 导出持久实例
curl http://source:8848/v1/ops/export/services > services-backup.json
```

### 11.2 导入

```bash
# 导入配置到新集群
curl -X POST http://target:8848/v1/ops/import/configs \
  -H "Content-Type: application/json" \
  -d @configs-backup.json

# 导入持久实例
curl -X POST http://target:8848/v1/ops/import/services \
  -H "Content-Type: application/json" \
  -d @services-backup.json
```

---

## 12. 集群运维

### 12.1 查看 Raft 状态

```bash
curl http://localhost:8848/v1/cluster/raft/status
# 返回: nodeId, role, leaderId, currentTerm, commitIndex
```

### 12.2 Leader 转移

```bash
curl -X POST "http://localhost:8848/v1/cluster/leader/transfer?targetId=node2-id"
```

### 12.3 移除故障节点

```bash
curl -X POST http://localhost:8848/v1/cluster/member/dead-node-id/remove
```

### 12.4 优雅关闭

服务端配置了 Spring Boot graceful shutdown (30s 超时)：

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

关闭时自动执行：排空 gRPC 连接 → 转移 Raft Leadership → 广播 Gossip 离开 → 停止服务。

---

## 13. 完整配置参考

```yaml
server:
  port: 8848
  shutdown: graceful

x-registry:
  grpc:
    port: 9848
    tls:
      enabled: false
      cert-path: ""
      key-path: ""
      trust-cert-path: ""
    rate-limit:
      requests-per-second: 1000
  session:
    max-connections: 50000
  health-check:
    interval-ms: 5000
    unhealthy-threshold-ms: 15000
    remove-threshold-ms: 30000
  cluster:
    enabled: false
    bind-address: 127.0.0.1
    gossip-port: 7848
    distro-port: 7849
    raft-port: 7850
    seed-nodes: []
    raft-data-dir: ./data/raft
    tls:
      enabled: false
      cert-path: ""
      key-path: ""
      trust-cert-path: ""
  auth:
    enabled: false
    encryption-key: ""
  storage:
    type: rocksdb
    rocksdb:
      path: ./data/rocksdb
    max-instance-count: 100000

spring:
  application:
    name: x-registry-server
  lifecycle:
    timeout-per-shutdown-phase: 30s

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    tags:
      application: x-registry-server

logging:
  level:
    com.x.registry: INFO
```
