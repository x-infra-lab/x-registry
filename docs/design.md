# X-Registry 设计文档

> 一个生产级的服务注册中心 & 配置中心统一实现

## 1. 项目目标

构建一个 Java 实现的、生产环境可用的注册中心 + 配置中心统一系统，对标 Nacos / SOFARegistry / Consul 的核心能力，同时保持架构简洁和可扩展。

### 1.1 核心需求

| 需求 | 说明 |
|------|------|
| 服务注册与发现 | 服务实例注册、注销、健康检查、变更推送 |
| 配置管理 | KV 配置存储、版本管理、变更监听、灰度发布 |
| 高可用 | 集群部署、无单点故障、故障自动转移 |
| 高性能 | 支持百万级服务实例、毫秒级变更推送 |
| 多租户 | Namespace 隔离，支持多环境/多团队 |
| 安全 | TLS 加密传输、Token 认证鉴权、审计日志 |
| 运维友好 | Prometheus 监控、Docker 容器化、管理控制台 |

### 1.2 设计原则

- **统一存储，语义分层**：注册中心和配置中心共享底层存储引擎和通知机制，在上层提供不同的语义 API
- **AP 优先，CP 可选**：服务发现默认 AP（可用性优先），配置管理默认 CP（一致性优先）
- **连接即健康**：借鉴 SOFARegistry，利用长连接本身作为健康检查的第一道防线
- **推拉结合**：核心变更走推送（低延迟），客户端定期全量校验（最终一致兜底）
- **生产优先**：默认配置面向生产环境（RocksDB 持久化、速率限制、容量保护）

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Client SDK                                  │
│  (Java / Spring Boot Starter / HTTP OpenAPI)                        │
└─────────────┬───────────────────────────────────────┬───────────────┘
              │ gRPC (长连接, TLS 可选)                 │ HTTP (OpenAPI)
              ▼                                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        X-Registry Server                             │
│                                                                     │
│  ┌───────────────┐  ┌───────────────┐  ┌─────────────────────────┐ │
│  │  Naming Module │  │ Config Module │  │     Console (Web UI)    │ │
│  │  (注册中心)    │  │ (配置中心)     │  │                         │ │
│  └───────┬───────┘  └───────┬───────┘  └─────────────────────────┘ │
│          │                   │                                       │
│  ┌───────▼───────────────────▼───────────────────────────────────┐  │
│  │                    Core Engine                                  │  │
│  │                                                                │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────────┐  │  │
│  │  │DistroAP  │ │ RaftCP   │ │ Notifier │ │ HealthChecker   │  │  │
│  │  │Protocol  │ │(Ratis)   │ │ (Push)   │ │                 │  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └─────────────────┘  │  │
│  │                                                                │  │
│  │  ┌──────────────────────────────────────────────────────────┐  │  │
│  │  │              Storage Engine (Pluggable)                   │  │  │
│  │  │   ┌─────────────┐  ┌────────────┐                       │  │  │
│  │  │   │ Memory Store│  │ RocksDB    │  (default)             │  │  │
│  │  │   └─────────────┘  └────────────┘                       │  │  │
│  │  └──────────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                   Security & Protection                        │  │
│  │   Auth (Token) + TLS + Rate Limit + Audit + Capacity Control  │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                   Cluster Protocol                              │  │
│  │   Member Discovery (Gossip SWIM) + Data Sync (Distro/Raft)    │  │
│  │   Inter-node TLS + Graceful Shutdown + Leadership Transfer    │  │
│  └────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.1 架构设计决策

| 维度 | 决策 | 参考来源 | 理由 |
|------|------|----------|------|
| 连接层 | Session/Data 分层 | SOFARegistry | 连接层无状态水平扩展，Data 层独立伸缩 |
| 集群发现 | Gossip (SWIM) | Consul | 去中心化、O(log N) 收敛、无需外部依赖 |
| 服务数据一致性 | Distro (AP) | Nacos | 注册中心可用性 > 一致性，分区容错 |
| 配置数据一致性 | Apache Ratis (CP) | Consul/Nacos | WAL 持久化、快照、Pre-vote、日志压缩 |
| 推送模型 | gRPC 双向流 + 聚合窗口 | SOFARegistry | 低延迟推送 + 100ms 聚合防止风暴 |
| 存储引擎 | RocksDB（默认）/ 内存 | 自研 | 生产环境持久化，测试用内存 |
| 传输安全 | TLS (客户端+集群间) | 行业标准 | 支持 mTLS，可选启用 |

---

## 3. 连接层架构：大规模 Client 接入

### 3.1 问题分析

注册/配置中心面对的核心挑战：

| 挑战 | 量级 | 影响 |
|------|------|------|
| 长连接数 | 10 万 - 100 万 | 单节点内存、fd 上限 |
| 心跳流量 | 万级 QPS（纯开销） | CPU、带宽浪费 |
| 推送扇出 | 一次变更 → 数万次推送 | 瞬时 IO 风暴 |
| 故障恢复 | 节点挂 → 数万客户端重连 | 连接风暴 (thundering herd) |

### 3.2 分层架构：Session / Data 分离

借鉴 SOFARegistry 的核心设计，将连接层（Session）与数据层（Data）解耦：

```
                      百万 Client
                          │
            ┌─────────────┼─────────────┐
            ▼             ▼             ▼
      ┌──────────┐ ┌──────────┐ ┌──────────┐
      │ Session-1│ │ Session-2│ │ Session-N│   ← 无状态，水平扩展
      │  (~50K)  │ │  (~50K)  │ │  (~50K)  │      只管连接 + 推送
      └────┬─────┘ └────┬─────┘ └────┬─────┘
           │             │             │
           └─────────────┼─────────────┘
                         │  内部 gRPC
                         ▼
            ┌─────────────────────────┐
            │       Data Layer         │   ← 有状态，存储 + 一致性
            │  (Distro/Raft, 3-5节点)  │
            └─────────────────────────┘
```

### 3.3 推送聚合

推送机制采用 100ms 时间窗口聚合 + 分批推送：

- 变更事件在 100ms 窗口内合并，同一服务的多次变更只触发一次推送
- 推送失败进入重试队列（指数退避，最大 30s）
- 客户端每 30s 全量拉取一次作为兜底

### 3.4 连接级优化

#### 单连接内存预算

| 组件 | 内存 |
|------|------|
| TCP 缓冲区 (read + write) | ~8 KB |
| gRPC stream 状态 | ~2 KB |
| 业务元数据 (clientId, subscriptions) | ~1 KB |
| **合计** | **~11 KB/连接** |

50K 连接 ≈ 550 MB 内存，单台 4C8G 即可承载。

### 3.5 心跳优化

采用连接级心跳替代实例级心跳，显著降低心跳开销：

| 方案 | 10 万实例心跳 QPS |
|------|-------------------|
| 传统（每实例 5s） | 20,000 |
| 连接级（每进程 5s，平均 5 实例/进程） | 4,000 |
| 连接级 + 自适应（稳态 15s） | ~1,300 |

gRPC KeepAlive 本身即心跳，零额外成本。服务端配置 `permitKeepAliveTime(5s)` 和 `permitKeepAliveWithoutCalls(true)` 允许客户端保持连接活跃。

### 3.6 连接保护

#### 速率限制

服务端实现了基于客户端 IP 的滑动窗口速率限制（`RateLimitInterceptor`）：

- 每个客户端 IP 独立计算，默认 1000 请求/秒
- 超限请求返回 `RESOURCE_EXHAUSTED` 状态码
- 通过配置 `x-registry.grpc.rate-limit.requests-per-second` 调整阈值

#### 实例容量保护

`InstanceStore` 内置实例容量上限保护（默认 100,000）：

- 超限时注册请求返回 `IllegalStateException`
- 已有实例的更新不受限制
- 通过配置 `x-registry.storage.max-instance-count` 调整上限

#### 连接风暴防护

客户端断连重连采用指数退避 + 随机抖动，避免雪崩。`ConnectionManager` 内置断路器模式：

- **CLOSED**（正常）→ 连续 5 次失败后 → **OPEN**（快速失败）
- **OPEN** → 30 秒冷却后 → **HALF_OPEN**（探测）
- **HALF_OPEN** → 连接成功 → **CLOSED** / 连接失败 → **OPEN**

---

## 4. 数据模型

### 4.1 统一数据模型

```
Namespace
 └── Group
      ├── Service (注册中心)
      │    └── Cluster
      │         └── Instance
      └── Config (配置中心)
           └── ConfigItem (key=dataId, value, version, md5)
```

### 4.2 核心实体定义

#### Service Instance（服务实例）

```java
public class Instance {
    private String instanceId;    // 全局唯一 (ip:port#clusterName#serviceName)
    private String serviceName;
    private String clusterName;
    private String ip;
    private int port;
    private double weight;        // 路由权重
    private boolean healthy;      // 健康状态
    private boolean enabled;      // 是否启用
    private boolean ephemeral;    // true=AP临时实例, false=CP持久实例
    private Map<String, String> metadata;
    private long lastHeartbeat;   // 最后心跳时间
    private long registerTime;
}
```

#### Config Item（配置项）

```java
public class ConfigItem {
    private String dataId;        // 配置ID
    private String group;
    private String namespace;
    private String content;       // 配置内容
    private String contentType;   // text/properties/yaml/json/xml
    private String md5;           // 内容摘要，用于变更检测
    private long version;         // 单调递增版本号
    private long lastModified;
    private String operator;      // 最后修改人
    private String description;
}
```

---

## 5. 核心模块设计

### 5.1 Naming Module（注册中心）

#### 5.1.1 服务注册

```
Client                          Server
  │                                │
  │── RegisterInstance(req) ──────>│  1. 校验参数 + 容量检查
  │                                │  2. 写入本地 Store
  │                                │  3. 异步 Distro 同步到其他节点
  │<── RegisterResponse ──────────│  4. 返回成功
  │                                │
  │── Heartbeat (定时) ──────────>│  5. 续约（ephemeral 实例）
```

**注册策略：**

- **临时实例 (ephemeral=true)**：AP 模式，使用 Distro 协议同步，客户端断连后自动摘除
- **持久实例 (ephemeral=false)**：CP 模式，使用 Raft 协议同步，需主动调用注销

#### 5.1.2 服务发现与推送

```
Client                          Server
  │                                │
  │── Subscribe(serviceName) ────>│  1. 注册 Watcher
  │<── Full ServiceInfo ─────────│  2. 返回当前全量
  │                                │
  │    ... 某实例上下线 ...         │
  │                                │  3. PushAggregator 聚合变更
  │<── Push(ServiceChanged) ─────│  4. 100ms 窗口后推送
  │── PushAck ───────────────────>│  5. 确认收到
  │                                │
  │── Pull (定时兜底) ───────────>│  6. 客户端定时全量校验
  │<── Full ServiceInfo ─────────│
```

#### 5.1.3 健康检查

采用三层健康检查机制：

| 层级 | 方式 | 适用场景 | 超时策略 |
|------|------|----------|----------|
| L1 | 连接存活检测 | gRPC 长连接客户端 | 连接断开立即摘除 |
| L2 | 客户端心跳 | HTTP 短连接客户端 | 15s 未心跳标记不健康，30s 摘除 |
| L3 | 服务端主动探测 | 持久实例 (TCP/HTTP) | 探测失败 3 次标记不健康 |

### 5.2 Config Module（配置中心）

#### 5.2.1 配置发布

```
Client/Console                   Server (Leader)
  │                                │
  │── PublishConfig(req) ────────>│  1. 参数校验
  │                                │  2. Raft 提案 → 多数派确认
  │                                │  3. Apply to State Machine
  │                                │  4. 存储历史版本
  │<── PublishResponse ──────────│  5. 返回 (version, md5)
  │                                │
  │                                │  6. Notifier 推送变更给订阅者
```

**一致性保证**：配置写入走 Raft 协议（Apache Ratis 实现），确保：
- 写入不丢失（WAL 持久化 + 多数派确认）
- 全局有序（单调递增 version）
- 自动快照（每 10,000 条日志触发）
- Pre-vote 防止脑裂

#### 5.2.2 配置监听

- 使用 gRPC 双向流维持长连接，变更时比较 md5 推送增量
- 支持 HTTP Long Polling 兼容非 gRPC 客户端
- 配置历史版本管理，支持版本回滚

#### 5.2.3 灰度发布

支持按 IP / Label 的灰度配置下发，通过 `GrayRule` 控制灰度范围和优先级。

---

## 6. 集群协议

### 6.1 成员发现 — Gossip (SWIM)

采用 SWIM 协议变体进行集群成员管理：

```
Node A              Node B              Node C
  │                   │                   │
  │── Ping ──────────>│                   │
  │<── Ack ──────────│                   │
  │                   │                   │
  │── Ping (timeout) ─────────────────────X  (C 无响应)
  │                   │                   │
  │── IndirectPing ──>│                   │
  │                   │── Ping ──────────>│  (B 代探 C)
  │                   │       (timeout)   X
  │                   │── Nack ──────────>│
  │<── Suspect(C) ───│                   │
  │                   │                   │
  │   ... Suspect 超时后标记 C Dead ...    │
```

**关键参数：**
- Probe interval: 1s
- Suspect timeout: 5s
- Indirect probes: 3 个节点
- 状态传播: Piggyback 在 ping/ack 消息中

**优雅退出**：节点关闭时调用 `gossipProtocol.leave()` 主动广播自身 DEAD 状态，3 次快速 ping 确保传播。

### 6.2 数据同步 — Distro Protocol (AP)

用于临时实例数据的集群间同步：

- 每个客户端连接由一个 "responsible node" 负责（hash 分配）
- 数据变更异步同步到所有其他节点
- 每个节点可独立提供读服务（AP，最终一致）
- 每 5s 执行数据校验，不一致时触发增量同步
- 节点加入/离开时，触发 responsible 重新分配 + 全量数据校验

### 6.3 一致性协议 — Apache Ratis (CP)

用于配置数据和持久实例，基于 Apache Ratis 3.2.2：

```
                 ┌─────────┐
          ┌─────│  Leader  │─────┐
          │     └─────────┘     │
   AppendEntries          AppendEntries
          │                     │
          ▼                     ▼
   ┌──────────┐          ┌──────────┐
   │ Follower │          │ Follower │
   └──────────┘          └──────────┘
```

**与自定义 Raft 实现相比，Apache Ratis 提供：**

| 特性 | 自定义实现 | Apache Ratis |
|------|-----------|--------------|
| WAL 持久化 | 无 | 内置 |
| 快照 | 无 | 自动触发 |
| Pre-vote | 无 | 内置 |
| 日志压缩 | 无 | 自动 |
| 动态成员变更 | 基本 | 完整支持 |
| 生产验证 | 未验证 | Apache 顶级项目 |

**状态机接口**（`RatisStateMachineAdapter`）：

- `applyTransaction()`: 反序列化日志条目 → 调用 `ConfigStateMachine.onApply()`
- `takeSnapshot()`: 调用 `configStateMachine.onSnapshotSave()`，写入 Ratis 快照目录
- `loadSnapshot()`: 从 Ratis 快照恢复，调用 `configStateMachine.onSnapshotLoad()`
- `notifyLeaderChanged()`: 触发 `onLeaderStart()`/`onLeaderStop()`

**关键配置：**
- 快照触发阈值: 每 10,000 条日志
- 数据目录: `{raft-data-dir}/{groupId}/`（WAL、快照、元数据均由 Ratis 管理）
- 传输: Ratis 内置 gRPC 传输（`ratis-grpc`），复用 `raftPort` 配置

---

## 7. 存储引擎

### 7.1 可插拔存储接口

```java
public interface KVStore {
    void put(String key, byte[] value);
    byte[] get(String key);
    void delete(String key);
    List<KVEntry> scan(String prefix);
    List<KVEntry> scan(String prefix, long afterVersion);
    long currentVersion();
}
```

### 7.2 存储实现

| 实现 | 适用场景 | 持久化 | 默认 |
|------|----------|--------|------|
| RocksDB | 生产环境 | 是 | **是** |
| ConcurrentHashMap | 开发/测试 | 否 | 否 |

生产环境默认使用 RocksDB (`x-registry.storage.type: rocksdb`)，数据持久化到 `./data/rocksdb`。

---

## 8. Client SDK 设计

### 8.1 SDK 核心能力

```java
// 注册中心 API
public interface NamingService {
    void registerInstance(String namespace, String serviceName, String group, Instance instance);
    void deregisterInstance(String namespace, String serviceName, String group, Instance instance);
    List<Instance> getInstances(String namespace, String serviceName, String group, boolean healthyOnly);
    void subscribe(String namespace, String serviceName, String group, Consumer<List<Instance>> listener);
}

// 配置中心 API
public interface ConfigService {
    ConfigItem getConfig(String namespace, String dataId, String group);
    boolean publishConfig(String namespace, String dataId, String group, String content, String contentType, String operator, String desc);
    boolean removeConfig(String namespace, String dataId, String group);
    void addListener(String namespace, String dataId, String group, Consumer<ConfigItem> listener);
}
```

### 8.2 SDK 容灾设计

```
┌──────────────────────────────────────────────────────┐
│                   Client SDK                          │
│                                                      │
│  ┌────────────┐  ┌─────────────┐  ┌──────────────┐  │
│  │ Connection │  │   Circuit   │  │   Request    │  │
│  │ Manager    │  │   Breaker   │  │   Deadline   │  │
│  └────────────┘  └─────────────┘  └──────────────┘  │
│                                                      │
│  ┌────────────┐  ┌─────────────┐  ┌──────────────┐  │
│  │    TLS     │  │  Heartbeat  │  │   Auth       │  │
│  │  Support   │  │  Sender     │  │   Token      │  │
│  └────────────┘  └─────────────┘  └──────────────┘  │
└──────────────────────────────────────────────────────┘
```

**生产级特性：**

- **断路器**：连续失败 5 次后自动熔断，30 秒冷却后探测恢复
- **请求超时**：所有 gRPC 阻塞调用设置 deadline（默认 5000ms），防止慢请求拖垮客户端
- **TLS 支持**：客户端可配置 TLS 证书，支持单向和双向 TLS
- **Token 认证**：支持通过 Bearer Token 认证
- **自动重连**：服务端不可达时指数退避重连（最大 30s，含随机抖动）

### 8.3 客户端配置

```java
XRegistryClientConfig config = new XRegistryClientConfig();
config.setServerAddr("127.0.0.1:9848");
config.setRequestTimeoutMs(5000);          // 请求超时
config.setTlsEnabled(true);               // 启用 TLS
config.setCertPath("/path/to/cert.pem");   // 客户端证书
config.setKeyPath("/path/to/key.pem");     // 客户端私钥
config.setTrustCertPath("/path/to/ca.pem");// CA 证书
config.setAuthToken("your-token");         // 认证 Token

XRegistryClient client = XRegistryClient.create(config);
```

---

## 9. 安全架构

### 9.1 传输层安全 (TLS)

支持三个层面的 TLS 加密：

| 层面 | 配置 | 说明 |
|------|------|------|
| 客户端 ↔ 服务端 | `x-registry.grpc.tls.*` | 客户端到服务端的 gRPC 连接 |
| 集群节点间 | `x-registry.cluster.tls.*` | Gossip/Distro 传输 + Ratis 传输 |
| Ratis 内部 | 自动使用集群 TLS 配置 | Ratis 原生支持 `GrpcTlsConfig` |

**服务端 TLS 配置示例：**

```yaml
x-registry:
  grpc:
    tls:
      enabled: true
      cert-path: /path/to/server.crt
      key-path: /path/to/server.key
      trust-cert-path: /path/to/ca.crt   # 可选，用于 mTLS
```

服务端在 `GrpcServerConfig` 中使用 `NettyServerBuilder.sslContext()`，支持 `ClientAuth.OPTIONAL` 模式。

### 9.2 认证与鉴权

**Token 认证**：

- 支持多 Token 管理（`TokenStore`），每个 Token 关联 Namespace 范围和权限列表
- 权限类型: `READ`、`WRITE`、`ADMIN`
- gRPC 通过 `AuthInterceptor` 拦截器验证 `authorization` metadata header
- HTTP 通过 `Bearer` 头验证

**Token 加密存储**（可选）：

- 配置 `x-registry.auth.encryption-key` 后，Token 密钥使用 AES-256-GCM 加密存储
- `TokenEncryptor` 工具类提供 `encrypt()` / `decrypt()` 方法

### 9.3 审计日志

`AuditLogger` 记录所有配置变更和管理操作：

- 自动清理审计记录中的敏感信息（匹配 `password`、`secret`、`token`、`key` 等模式）
- 配置内容截断到 200 字符防止日志膨胀
- 通过 `x-registry.audit.sensitive-patterns` 自定义敏感模式

### 9.4 速率限制

`RateLimitInterceptor` 对 gRPC 请求实施基于客户端 IP 的速率限制：

- 滑动窗口算法，1 秒时间窗口
- 默认 1000 请求/秒/IP
- 超限返回 `RESOURCE_EXHAUSTED` gRPC 状态码
- 每个客户端 IP 独立计算，ConcurrentHashMap 实现零锁竞争

---

## 10. 通信协议

### 10.1 gRPC 接口定义

```protobuf
// 注册中心服务
service NamingService {
    rpc RegisterInstance(RegisterInstanceRequest) returns (RegisterInstanceResponse);
    rpc DeregisterInstance(DeregisterInstanceRequest) returns (DeregisterInstanceResponse);
    rpc QueryInstances(QueryInstancesRequest) returns (QueryInstancesResponse);
    rpc Subscribe(stream SubscribeRequest) returns (stream NotifyEvent);
    rpc BatchHeartbeat(BatchHeartbeatRequest) returns (BatchHeartbeatResponse);
}

// 配置中心服务
service ConfigService {
    rpc GetConfig(GetConfigRequest) returns (GetConfigResponse);
    rpc PublishConfig(PublishConfigRequest) returns (PublishConfigResponse);
    rpc DeleteConfig(DeleteConfigRequest) returns (DeleteConfigResponse);
    rpc WatchConfig(stream WatchConfigRequest) returns (stream ConfigChangeEvent);
    rpc ListConfigHistory(ListConfigHistoryRequest) returns (ListConfigHistoryResponse);
}

// 集群内部通信
service ClusterService {
    rpc Ping(PingRequest) returns (PingResponse);
    rpc IndirectPing(IndirectPingRequest) returns (IndirectPingResponse);
    rpc DistroSync(DistroSyncRequest) returns (DistroSyncResponse);
    rpc DistroVerify(DistroVerifyRequest) returns (DistroVerifyResponse);
}
```

### 10.2 HTTP OpenAPI

```
# 注册中心
POST   /v1/ns/{namespace}/naming/instance          # 注册实例
DELETE /v1/ns/{namespace}/naming/instance          # 注销实例
GET    /v1/ns/{namespace}/naming/instances         # 查询实例列表
PUT    /v1/ns/{namespace}/naming/instance/beat     # 心跳续约

# 配置中心
GET    /v1/ns/{namespace}/config                   # 获取配置
POST   /v1/ns/{namespace}/config                   # 发布配置
DELETE /v1/ns/{namespace}/config                   # 删除配置
GET    /v1/ns/{namespace}/config/history           # 查询历史
POST   /v1/ns/{namespace}/config/gray              # 灰度发布
POST   /v1/ns/{namespace}/config/gray/promote      # 灰度推全

# 集群管理
GET    /v1/cluster/health                          # 健康状态
GET    /v1/cluster/members                         # 成员列表
GET    /v1/cluster/leader                          # 当前 Leader
GET    /v1/cluster/raft/status                     # Raft 状态
POST   /v1/cluster/leader/transfer                 # Leader 转移
POST   /v1/cluster/member/{id}/remove              # 移除成员

# 运维 API
GET    /v1/ops/metrics/summary                     # 指标摘要
GET    /v1/ops/connections                         # 连接列表
POST   /v1/ops/connections/{id}/close              # 关闭连接
GET    /v1/ops/export/configs                      # 导出配置
POST   /v1/ops/import/configs                      # 导入配置

# 控制台 API
GET    /v1/console/namespaces                      # 命名空间列表
GET    /v1/console/services                        # 服务列表
GET    /v1/console/configs                         # 配置列表
```

---

## 11. 模块划分与工程结构

```
x-registry/
├── x-registry-api/                  # 公共 API 定义 (proto, model, SPI)
│   ├── src/main/proto/              # gRPC proto 文件
│   │   ├── naming.proto             # 注册中心协议
│   │   ├── config.proto             # 配置中心协议
│   │   └── cluster.proto            # 集群通信协议
│   └── src/main/java/
│       └── com.x.registry.api/
│           ├── model/               # Instance, ConfigItem, Service 等
│           ├── naming/              # NamingService 接口
│           ├── config/              # ConfigService 接口
│           └── exception/           # 统一异常定义
│
├── x-registry-server/               # 服务端核心 (~92 source files)
│   └── src/main/java/
│       └── com.x.registry.server/
│           ├── boot/                # 启动配置 (Spring Configuration)
│           ├── naming/              # ServiceManager, HealthChecker
│           ├── config/              # ConfigManager, ConfigStore
│           ├── cluster/             # 集群协议
│           │   ├── gossip/          # SWIM 成员发现
│           │   ├── distro/          # AP 数据同步
│           │   └── raft/            # Apache Ratis CP 一致性
│           ├── storage/             # InstanceStore, KVStore, RocksDB
│           ├── grpc/                # gRPC 服务实现 + 拦截器
│           ├── http/                # HTTP OpenAPI + Console
│           ├── security/            # Auth, Token, Audit
│           └── push/                # PushAggregator 推送聚合
│
├── x-registry-client/               # Java Client SDK
│   └── src/main/java/
│       └── com.x.registry.client/
│           ├── naming/              # NamingService 客户端
│           ├── config/              # ConfigService 客户端
│           └── connection/          # ConnectionManager, Circuit Breaker
│
├── x-registry-spring-boot-starter/  # Spring Boot 自动配置
│
├── x-registry-benchmark/            # JMH 性能基准测试
│   └── src/main/java/
│       └── com.x.registry.benchmark/
│           ├── RegisterBenchmark        # 注册吞吐
│           ├── DiscoveryBenchmark       # 发现吞吐
│           ├── ConfigPublishBenchmark   # 配置发布吞吐
│           ├── PushLatencyBenchmark     # 推送延迟
│           └── ClusterStressBenchmark   # 集群压测 (8线程)
│
├── x-registry-tests/                # E2E 集成测试
│   └── src/test/java/
│       └── com.x.registry.tests/
│           └── ClusterE2ETest       # 9 个集群端到端测试
│
├── Dockerfile                       # Docker 镜像构建
├── docker-compose.yml               # 3 节点集群编排
├── .github/workflows/ci.yml         # CI/CD (build + E2E + Docker)
└── docs/
    ├── design.md                    # 本文档
    └── deployment.md                # 部署指南
```

---

## 12. 关键流程

### 12.1 Server 启动流程

```
1. 加载配置 (application.yaml / 启动参数)
2. 初始化 Storage Engine (RocksDB 默认)
3. 启动 gRPC Server (含 TLS, Rate Limit, Auth 拦截器)
4. 启动 HTTP Server (Naming/Config/Console API)
5. 启动 HealthChecker
6. [集群模式] 启动 Gossip → 加入集群、发现成员
7. [集群模式] 启动 Distro → 从其他节点全量同步数据
8. [集群模式] 启动 Apache Ratis → 选举 Leader / 加载快照
9. 标记自身为 Ready，开始接收客户端请求
```

### 12.2 节点加入集群

```
New Node                    Existing Cluster
  │                              │
  │── Join(seed nodes) ─────────>│  Gossip: 加入
  │<── MemberList ──────────────│  获取成员列表
  │                              │
  │── DistroFullSync ──────────>│  请求全量注册数据
  │<── DistroData ──────────────│
  │                              │
  │── RaftAddPeer ─────────────>│  加入 Ratis 组
  │<── Snapshot + Logs ─────────│  自动同步
  │                              │
  │   [Ready to serve]           │
```

### 12.3 优雅关闭流程

```
1. 收到 SIGTERM/SIGINT
2. Spring Boot graceful shutdown 开始（30s 超时）
3. gRPC Server.shutdown() → 排空在途请求
4. [集群模式] 如果是 Leader → 尝试 Leadership 转移（5s 超时）
5. [集群模式] Gossip leave() → 广播自身 DEAD 状态
6. [集群模式] 停止 Distro 同步
7. [集群模式] 停止 Ratis 服务
8. 等待所有请求完成（30s）→ 强制关闭
9. 进程退出
```

### 12.4 节点故障处理

```
Node A (Dead)     Node B            Node C
                    │                  │
      (Gossip 检测到 A 不可达)         │
                    │                  │
                    │── Suspect(A) ──>│  广播 Suspect
                    │                  │
        (5s 后未恢复)                  │
                    │                  │
                    │── Dead(A) ─────>│  广播 Dead
                    │                  │
     (触发 Distro Responsible 重分配)  │
     (A 负责的客户端被 B/C 接管)       │
     (A 上的临时实例被摘除)            │
     (Ratis 自动重新选举)              │
```

---

## 13. 生产环境特性

### 13.1 高可用保障

| 场景 | 应对策略 |
|------|----------|
| 单节点故障 | Gossip 检测 + 自动 failover，客户端自动重连 + 断路器 |
| 网络分区 | AP 数据各分区独立服务；CP 数据少数派只读 |
| 全集群不可用 | 客户端降级读本地快照 |
| 脑裂恢复 | Distro 校验 + 数据合并，Raft (Ratis) Pre-vote 防止脑裂 |
| Leader 故障 | Ratis 自动选举，可手动触发 Leadership 转移 |
| 计划内下线 | 优雅关闭：排空连接 → 转移 Leader → 广播离开 |

### 13.2 性能优化

- **推送聚合**：100ms 窗口内的多次变更合并为一次推送
- **持久化存储**：RocksDB LSM-Tree，高写入吞吐
- **连接复用**：单个 gRPC 连接承载注册 + 配置 + 心跳
- **批量心跳**：同一客户端上的多个实例心跳合并
- **增量同步**：Distro 优先增量，定时全量校验
- **请求超时**：客户端 gRPC deadline 防止慢请求阻塞

### 13.3 可观测性

Prometheus 指标通过 `/actuator/prometheus` 暴露：

```
x_registry_instance_register_total        # 注册次数
x_registry_instance_deregister_total      # 注销次数
x_registry_config_publish_total           # 配置发布次数
x_registry_push_total                     # 推送次数
x_registry_push_latency_seconds           # 推送延迟
x_registry_grpc_connection_count          # gRPC 连接数
```

### 13.4 容器化部署

提供 Dockerfile + docker-compose.yml，支持一键部署 3 节点集群：

```bash
# 构建镜像
docker build -t x-registry .

# 启动 3 节点集群
docker-compose up -d
```

---

## 14. 技术栈

| 组件 | 选型 | 版本 |
|------|------|------|
| 构建工具 | Maven (multi-module) | 3.8+ |
| Java 版本 | JDK 17+ | LTS |
| 网络框架 | gRPC-Java (grpc-netty-shaded) | 1.62.2 |
| HTTP 框架 | Spring Boot WebFlux | 3.2.5 |
| Raft 实现 | Apache Ratis | 3.2.2 |
| 存储引擎 | RocksDB (JNI) | 9.0.0 |
| 序列化 | Protobuf | 3.25.3 |
| 指标 | Micrometer + Prometheus | Spring Boot 内置 |
| 日志 | SLF4J + Logback | 标准 |
| 测试 | JUnit 5 + Mockito | 5.x |
| 性能测试 | JMH | 1.37 |
| 容器化 | Docker + Compose | - |
| CI/CD | GitHub Actions | - |

---

## 15. 与竞品对比

| 特性 | X-Registry | Nacos | SOFARegistry | Consul |
|------|-----------|-------|-------------|--------|
| 语言 | Java | Java | Java | Go |
| 注册中心 | ✅ | ✅ | ✅ | ✅ |
| 配置中心 | ✅ | ✅ | ❌ | ✅ (KV) |
| AP 协议 | Distro | Distro | 自研分层 | ❌ |
| CP 协议 | Apache Ratis | JRaft | Raft (Meta) | Raft |
| 推送方式 | gRPC 双向流 | UDP+LongPoll | TCP Push | Blocking Query |
| 健康检查 | 连接+心跳+探测 | 心跳+探测 | 连接 | Agent 本地检查 |
| 集群发现 | Gossip SWIM | 配置文件/寻址 | Meta 层管理 | Gossip |
| TLS | 全链路支持 | 部分 | 部分 | 全链路 |
| 速率限制 | 内置 | 外部依赖 | 外部依赖 | 外部依赖 |
| 断路器 | SDK 内置 | 无 | 无 | 无 |
| 管理控制台 | ✅ | ✅ | ❌ | ✅ |
| Spring Boot | ✅ | ✅ | ✅ | ✅ |

---

## 16. 测试覆盖

### 16.1 测试分层

| 层级 | 测试数 | 说明 |
|------|--------|------|
| 单元测试 | 122 | 核心组件独立测试 (JUnit 5 + Mockito) |
| 集成测试 | 6 | Spring 上下文集成测试 |
| E2E 测试 | 9 | 3 节点集群端到端测试 |
| 基准测试 | 9 | JMH 性能基准 (含集群压测) |
| **合计** | **131+** | |

### 16.2 关键测试覆盖

| 测试类 | 测试项 |
|--------|--------|
| MemberManagerTest | 成员增删改、状态转换、并发访问 (12 tests) |
| ConfigStateMachineTest | 日志应用、快照保存/加载、监听通知 (13 tests) |
| ConfigManagerTest | CRUD、版本管理、回滚、监听 (11 tests) |
| ServiceManagerTest | 注册/注销、健康检查、订阅推送 (8 tests) |
| GossipProtocolTest | Ping/Ack、Suspect 检测、Dead 声明 (7 tests) |
| DistroProtocolTest | 同步/校验、全量同步、回调 (8 tests) |
| InstanceStoreTest | 注册/查询、容量限制 (10 tests) |
| RateLimitInterceptorTest | 限流触发、多 IP 隔离 (4 tests) |
| TokenStoreTest | Token 增删验证、加密存储 (9 tests) |
| AuthInterceptorTest | 认证通过/拒绝、命名空间检查 (4 tests) |
| ClusterControllerTest | 健康/成员/Leader/Raft 状态 API (11 tests) |

---

## 17. 开源规范

- **License**: Apache License 2.0
- **代码规范**: Google Java Style Guide
- **分支模型**: main + feature branches，PR Review
- **版本号**: Semantic Versioning (SemVer)
- **CI/CD**: GitHub Actions (build + E2E + Docker)
