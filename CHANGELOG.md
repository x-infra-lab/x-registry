# Changelog

All notable changes to X-Registry are documented in this file.

## [1.0.0] - 2026-07-10

### Core Infrastructure (Phase 0)

**Project scaffolding and single-node implementation:**

- Set up Maven multi-module project: `x-registry-api`, `x-registry-server`, `x-registry-client`, `x-registry-spring-boot-starter`, `x-registry-benchmark`, `x-registry-tests`
- Implemented Protobuf definitions for naming, config, and cluster services (`naming.proto`, `config.proto`, `cluster.proto`)
- Implemented service registration and discovery (`ServiceManager`, `InstanceStore`, `NamingGrpcServiceImpl`)
- Implemented configuration management with version history (`ConfigManager`, `ConfigStore`, `ConfigGrpcServiceImpl`)
- Implemented health checker with configurable thresholds (`HealthChecker`)
- Implemented push notification with 100ms aggregation window (`PushAggregator`)
- Implemented gray/canary release for configurations
- Implemented Java client SDK with `NamingService` and `ConfigService`
- Implemented Spring Boot Starter with auto-discovery (`XRegistryDiscoveryClient`)
- Implemented management console Web UI (`ConsoleController`)
- Implemented HTTP OpenAPI endpoints for naming, config, and ops
- Implemented RocksDB persistent storage engine (`RocksDBKVStore`)
- Implemented in-memory storage engine (`MemoryKVStore`)
- Added Micrometer metrics with Prometheus endpoint

### Cluster Protocols (Phase 0)

- Implemented Gossip SWIM protocol for member discovery (`GossipProtocol`, `GrpcGossipTransport`)
- Implemented Distro AP protocol for ephemeral instance sync (`DistroProtocol`, `GrpcDistroTransport`)
- Implemented custom Raft CP protocol for config + persistent instances
- Implemented cluster bootstrap configuration (`ClusterManager`, `ClusterBootConfig`)
- Added 9 E2E cluster tests (`ClusterE2ETest`)

### Phase 1: Replace Custom Raft with Apache Ratis (P0)

- **Added** Apache Ratis 3.2.2 dependencies (`ratis-server`, `ratis-grpc`, `ratis-common`)
- **Added** `RatisStateMachineAdapter` — bridges Ratis `BaseStateMachine` to `ConfigStateMachine`, handling `applyTransaction()`, `takeSnapshot()`, `loadSnapshot()`, and `notifyLeaderChanged()`
- **Added** `RatisServer` — wraps `RaftServer` lifecycle with `start()`, `propose()`, `isLeader()`, `getLeaderId()`, `addPeer()`, `removePeer()`, `transferLeadership()`
- **Added** `RatisConfig` — Ratis-specific configuration (dataDir, groupId, election/heartbeat timeouts, snapshot threshold)
- **Modified** `ClusterManager` — replaced `RaftNode` + `GrpcRaftTransport` with `RatisServer`
- **Deleted** `RaftNode.java` (407 lines custom Raft implementation)
- **Deleted** `GrpcRaftTransport.java` (146 lines custom gRPC transport)
- **Deleted** `RaftTransport.java` (8 lines interface)
- **Kept** `LogEntry`, `RaftStateMachine`, `ConfigStateMachine` interfaces unchanged

### Phase 2: Graceful Shutdown (P0)

- **Modified** `GrpcServerConfig.destroy()` — implemented proper drain: `server.shutdown()` → `awaitTermination(30s)` → `shutdownNow()` with interrupt handling
- **Modified** `ClusterManager.stop()` — enhanced shutdown sequence: leadership transfer (5s timeout) → gossip leave → distro stop → Ratis stop
- **Added** `GossipProtocol.leave()` — marks self as DEAD and sends 3 rapid pings to propagate leave quickly
- **Configured** Spring Boot graceful shutdown (`server.shutdown: graceful`, `timeout-per-shutdown-phase: 30s`)

### Phase 3: Security Hardening (P0)

**3a: gRPC TLS Support**
- **Modified** `GrpcServerConfig` — added `@Value` fields for TLS (`tls.enabled`, `tls.cert-path`, `tls.key-path`, `tls.trust-cert-path`); when enabled, configures `NettyServerBuilder.sslContext()` with `ClientAuth.OPTIONAL`
- **Modified** `ConnectionManager` (client) — added TLS support via `NettyChannelBuilder.sslContext()`
- **Modified** `XRegistryClientConfig` — added `tlsEnabled`, `certPath`, `keyPath`, `trustCertPath`, `authToken` fields
- **Modified** `GrpcGossipTransport` — added `setTlsContext()` for inter-node TLS
- **Modified** `GrpcDistroTransport` — added `setTlsContext()` for inter-node TLS
- **Modified** `ClusterManager.start()` — constructs `SslContext` from `ClusterConfig` TLS settings for gossip/distro transports
- **Modified** `ClusterConfig` — added `tlsEnabled`, `certPath`, `keyPath`, `trustCertPath` fields
- **Modified** `ClusterBootConfig` — wired `@Value` annotations for cluster TLS properties
- **Updated** `application.yaml` — added `x-registry.grpc.tls.*` and `x-registry.cluster.tls.*` configuration sections

**3b: Token Storage Encryption**
- **Added** `TokenEncryptor` utility class — AES-256-GCM encryption/decryption for token secrets
- **Modified** `TokenStore` — optional token encryption when `encryption-key` is configured

**3c: Audit Log Sanitization**
- **Modified** `AuditLogger` — added sensitive data masking (configurable patterns: password, secret, token, key), config content truncation to 200 chars

### Phase 4: Test Coverage (P0)

- **Added** `MemberManagerTest` — 12 tests covering member state transitions, concurrent access
- **Added** `ConfigStateMachineTest` — 13 tests covering log application, snapshot save/load, watcher notification
- **Added** `ConfigManagerTest` — 11 tests covering CRUD, version management, rollback, watch
- **Added** `ServiceManagerTest` — 8 tests covering register/deregister, health check, subscriber notification
- **Added** `GossipProtocolTest` — 7 tests covering ping/ack, suspect detection, dead declaration
- **Added** `DistroProtocolTest` — 8 tests covering sync/verify, full sync, listener callback
- **Added** `AuthInterceptorTest` — 4 tests covering valid/invalid/missing token, namespace check
- **Added** `TokenStoreTest` — 9 tests covering add/validate, encryption/decryption
- **Added** `ConfigGrpcServiceTest` — 6 tests covering publish, get, listen, cluster forwarding
- **Added** `NamingGrpcServiceTest` — 5 tests covering register, query, subscribe, push
- **Added** `ClusterControllerTest` — 11 tests covering health, members, leader, raft status, leader transfer, member removal
- **Added** `InstanceStoreTest` — 10 tests covering register/query, capacity limiting
- **Added** `RateLimitInterceptorTest` — 4 tests covering rate limit triggering, multi-IP isolation
- **Added** `OpsControllerTest` — 5 tests covering metrics, connections, close connection
- **Added** `PushAggregatorTest` — 3 tests covering push aggregation
- Total: **122 unit tests + 9 E2E tests = 131 tests, 0 failures**

### Phase 5: Client Retry Enhancement (P1)

- **Modified** `ConnectionManager` — added circuit breaker state machine:
  - States: `CLOSED` → `OPEN` (after 5 consecutive failures) → `HALF_OPEN` (after 30s cooldown) → `CLOSED` (on success)
  - Fast-fail in `OPEN` state, probe in `HALF_OPEN`
- **Modified** `XRegistryClientConfig` — added `circuitBreakerEnabled`, `circuitBreakerFailureThreshold`, `circuitBreakerCooldownMs`

### Phase 6: Ops Tools API (P1)

- **Modified** `ClusterController` — added 3 endpoints:
  - `GET /v1/cluster/raft/status` — returns nodeId, role, leaderId, currentTerm, commitIndex
  - `POST /v1/cluster/leader/transfer` — triggers Raft leadership transfer
  - `POST /v1/cluster/member/{memberId}/remove` — removes member from cluster
- **Added** `OpsController` — operations endpoints:
  - `GET /v1/ops/metrics/summary` — aggregated metrics (connections, configs, services)
  - `GET /v1/ops/connections` — list active gRPC connections
  - `POST /v1/ops/connections/{connectionId}/close` — force-close connection

### Phase 7: CI/CD and Containerization (P1)

- **Added** `Dockerfile` — multi-stage Docker image based on `eclipse-temurin:17-jre-alpine`, with health check
- **Added** `docker-compose.yml` — 3-node cluster setup with persistent volumes, bridge network, health checks
- **Modified** `.github/workflows/ci.yml` — expanded from single job to 3 jobs:
  - `build`: compile + unit tests
  - `e2e-tests`: cluster E2E tests (depends on build)
  - `docker`: build image + smoke test (main branch only)

### Phase 8: Benchmark Validation (P1)

- **Added** `ClusterStressBenchmark` — 8-thread JMH benchmark with 4 scenarios: configPublishStress, discoveryStress, registerDeregisterStress, configReadStress

### Production Risk Fixes

- **Changed** default `x-registry.storage.type` from `memory` to `rocksdb` in `application.yaml` — production deployments must use persistent storage
- **Added** client request deadlines — `withDeadlineAfter(requestTimeoutMs, MILLISECONDS)` on all blocking stubs in `ConfigServiceImpl` (4 calls) and `NamingServiceImpl` (4 calls)
- **Added** `RateLimitInterceptor` — per-client-IP sliding window rate limiter (default 1000 req/s), returns `RESOURCE_EXHAUSTED` on excess
- **Added** instance capacity protection to `InstanceStore` — configurable `max-instance-count` (default 100,000), rejects new registrations when full
- **Added** inter-node TLS support — `ClusterConfig`, `GrpcGossipTransport`, `GrpcDistroTransport` all support optional TLS
- **Added** `requestTimeoutMs` field to `XRegistryClientConfig` (default 5000ms)
- **Modified** `StorageConfig` — wires `maxInstanceCount` from configuration to `InstanceStore`
- **Modified** `GrpcServerConfig` — added `RateLimitInterceptor` to gRPC server interceptor chain

### Documentation

- **Updated** `docs/design.md` — comprehensive architecture document reflecting all production hardening changes
- **Updated** `docs/deployment.md` — complete deployment guide with TLS, Docker, rate limiting, cluster operations
- **Added** `CHANGELOG.md` — this file
