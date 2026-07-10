package com.x.registry.client.config;

import com.x.registry.api.config.ConfigService;
import com.x.registry.api.grpc.*;
import com.x.registry.api.model.ConfigItem;
import com.x.registry.client.XRegistryClientConfig;
import com.x.registry.client.cache.LocalCacheManager;
import com.x.registry.client.connection.ConnectionManager;
import com.x.registry.client.event.ConfigChangeEvent;
import com.x.registry.client.event.EventBus;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ConfigServiceImpl implements ConfigService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigServiceImpl.class);

    private final XRegistryClientConfig config;
    private final ConnectionManager connectionManager;
    private final LocalCacheManager cacheManager;
    private final EventBus eventBus;
    private final Map<String, StreamObserver<WatchConfigRequest>> watchStreams = new ConcurrentHashMap<>();

    public ConfigServiceImpl(XRegistryClientConfig config, ConnectionManager connectionManager,
                             LocalCacheManager cacheManager, EventBus eventBus) {
        this.config = config;
        this.connectionManager = connectionManager;
        this.cacheManager = cacheManager;
        this.eventBus = eventBus;
    }

    @Override
    public ConfigItem getConfig(String namespace, String dataId, String group) {
        try {
            ConfigGrpcServiceGrpc.ConfigGrpcServiceBlockingStub stub =
                    ConfigGrpcServiceGrpc.newBlockingStub(connectionManager.getChannel())
                    .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

            GetConfigRequest request = GetConfigRequest.newBuilder()
                    .setNamespace(namespace)
                    .setDataId(dataId)
                    .setGroup(group)
                    .build();

            GetConfigResponse response = stub.getConfig(request);
            if (!response.getFound()) {
                return null;
            }
            ConfigItem item = fromProto(response.getConfig());
            cacheManager.cacheConfig(namespace, group, dataId, item);
            return item;
        } catch (Exception e) {
            log.warn("Failed to get config from server, falling back to local cache: {}", e.getMessage());
            ConfigItem cached = cacheManager.getCachedConfig(namespace, group, dataId);
            if (cached != null) {
                log.info("Returning cached config for {}/{}/{}", namespace, group, dataId);
                return cached;
            }
            throw new RuntimeException("Server unavailable and no cached config for " + dataId, e);
        }
    }

    @Override
    public boolean publishConfig(String namespace, String dataId, String group, String content,
                                 String contentType, String operator, String description) {
        ConfigGrpcServiceGrpc.ConfigGrpcServiceBlockingStub stub =
                ConfigGrpcServiceGrpc.newBlockingStub(connectionManager.getChannel())
                    .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

        PublishConfigRequest request = PublishConfigRequest.newBuilder()
                .setNamespace(namespace)
                .setDataId(dataId)
                .setGroup(group)
                .setContent(content)
                .setContentType(contentType != null ? contentType : "text")
                .setOperator(operator != null ? operator : "")
                .setDescription(description != null ? description : "")
                .build();

        PublishConfigResponse response = stub.publishConfig(request);
        return response.getSuccess();
    }

    @Override
    public boolean removeConfig(String namespace, String dataId, String group, String operator) {
        ConfigGrpcServiceGrpc.ConfigGrpcServiceBlockingStub stub =
                ConfigGrpcServiceGrpc.newBlockingStub(connectionManager.getChannel())
                    .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

        DeleteConfigRequest request = DeleteConfigRequest.newBuilder()
                .setNamespace(namespace)
                .setDataId(dataId)
                .setGroup(group)
                .setOperator(operator != null ? operator : "")
                .build();

        DeleteConfigResponse response = stub.deleteConfig(request);
        return response.getSuccess();
    }

    @Override
    public List<ConfigItem> listHistory(String namespace, String dataId, String group, int page, int pageSize) {
        ConfigGrpcServiceGrpc.ConfigGrpcServiceBlockingStub stub =
                ConfigGrpcServiceGrpc.newBlockingStub(connectionManager.getChannel())
                    .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);

        ListConfigHistoryRequest request = ListConfigHistoryRequest.newBuilder()
                .setNamespace(namespace)
                .setDataId(dataId)
                .setGroup(group)
                .setPage(page)
                .setPageSize(pageSize)
                .build();

        ListConfigHistoryResponse response = stub.listConfigHistory(request);
        List<ConfigItem> result = new ArrayList<>();
        for (ConfigItemProto proto : response.getRevisionsList()) {
            result.add(fromProto(proto));
        }
        return result;
    }

    @Override
    public void addListener(String namespace, String dataId, String group, Consumer<ConfigItem> listener) {
        ConfigGrpcServiceGrpc.ConfigGrpcServiceStub asyncStub =
                ConfigGrpcServiceGrpc.newStub(connectionManager.getChannel());

        StreamObserver<WatchConfigRequest> requestObserver = asyncStub.watchConfig(new StreamObserver<>() {
            @Override
            public void onNext(ConfigChangeEventProto event) {
                ConfigItem item = new ConfigItem(event.getNamespace(), event.getGroup(), event.getDataId());
                item.setContent(event.getContent());
                item.setMd5(event.getMd5());
                item.setVersion(event.getVersion());
                cacheManager.cacheConfig(event.getNamespace(), event.getGroup(), event.getDataId(), item);
                if (eventBus != null) {
                    eventBus.publish(new ConfigChangeEvent(event.getNamespace(), event.getGroup(), event.getDataId(), item));
                }
                listener.accept(item);
            }

            @Override
            public void onError(Throwable t) {
                log.error("Config watch stream error for {}/{}/{}", namespace, group, dataId, t);
            }

            @Override
            public void onCompleted() {
                log.info("Config watch stream completed for {}/{}/{}", namespace, group, dataId);
            }
        });

        requestObserver.onNext(WatchConfigRequest.newBuilder()
                .setNamespace(namespace)
                .setDataId(dataId)
                .setGroup(group)
                .build());

        String key = namespace + "@@" + group + "@@" + dataId;
        watchStreams.put(key, requestObserver);
    }

    @Override
    public void removeListener(String namespace, String dataId, String group, Consumer<ConfigItem> listener) {
        String key = namespace + "@@" + group + "@@" + dataId;
        StreamObserver<WatchConfigRequest> stream = watchStreams.remove(key);
        if (stream != null) {
            stream.onCompleted();
        }
    }

    @Override
    public void close() {
        watchStreams.values().forEach(StreamObserver::onCompleted);
        watchStreams.clear();
    }

    private ConfigItem fromProto(ConfigItemProto proto) {
        ConfigItem item = new ConfigItem(proto.getNamespace(), proto.getGroup(), proto.getDataId());
        item.setContent(proto.getContent());
        item.setContentType(proto.getContentType());
        item.setMd5(proto.getMd5());
        item.setVersion(proto.getVersion());
        item.setLastModified(proto.getLastModified());
        item.setOperator(proto.getOperator());
        item.setDescription(proto.getDescription());
        return item;
    }
}
