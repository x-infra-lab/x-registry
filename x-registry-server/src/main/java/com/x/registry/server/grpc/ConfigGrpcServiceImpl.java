package com.x.registry.server.grpc;

import com.x.registry.api.grpc.*;
import com.x.registry.api.model.ConfigItem;
import com.x.registry.server.config.ConfigManager;
import com.x.registry.server.config.ConfigWatcherManager;
import com.x.registry.server.config.GrayRuleManager;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Component
public class ConfigGrpcServiceImpl extends ConfigGrpcServiceGrpc.ConfigGrpcServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ConfigGrpcServiceImpl.class);

    private final ConfigManager configManager;
    private final ConfigWatcherManager watcherManager;
    private final GrayRuleManager grayRuleManager;

    public ConfigGrpcServiceImpl(ConfigManager configManager, ConfigWatcherManager watcherManager,
                                 GrayRuleManager grayRuleManager) {
        this.configManager = configManager;
        this.watcherManager = watcherManager;
        this.grayRuleManager = grayRuleManager;
    }

    @Override
    public void getConfig(GetConfigRequest request, StreamObserver<GetConfigResponse> responseObserver) {
        ConfigItem item = configManager.getConfig(request.getNamespace(), request.getDataId(), request.getGroup());
        GetConfigResponse.Builder builder = GetConfigResponse.newBuilder();
        if (item != null) {
            // Check gray rules against client IP
            String clientIp = ConnectionInterceptor.CLIENT_IP_KEY.get();
            String resolvedContent = grayRuleManager.resolveContent(
                    request.getNamespace(), request.getGroup(), request.getDataId(),
                    clientIp, Collections.emptySet(), item.getContent());
            if (!resolvedContent.equals(item.getContent())) {
                ConfigItem grayItem = new ConfigItem(item.getNamespace(), item.getGroup(), item.getDataId());
                grayItem.setContent(resolvedContent);
                grayItem.setContentType(item.getContentType());
                grayItem.setVersion(item.getVersion());
                grayItem.setLastModified(item.getLastModified());
                grayItem.setOperator(item.getOperator());
                grayItem.setDescription(item.getDescription());
                builder.setFound(true).setConfig(toProto(grayItem));
            } else {
                builder.setFound(true).setConfig(toProto(item));
            }
        } else {
            builder.setFound(false);
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void publishConfig(PublishConfigRequest request, StreamObserver<PublishConfigResponse> responseObserver) {
        try {
            ConfigItem item = configManager.publishConfig(
                    request.getNamespace(), request.getDataId(), request.getGroup(),
                    request.getContent(), request.getContentType(),
                    request.getOperator(), request.getDescription());
            responseObserver.onNext(PublishConfigResponse.newBuilder()
                    .setSuccess(true)
                    .setVersion(item.getVersion())
                    .setMd5(item.getMd5())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(PublishConfigResponse.newBuilder()
                    .setSuccess(false).setMessage(e.getMessage()).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void deleteConfig(DeleteConfigRequest request, StreamObserver<DeleteConfigResponse> responseObserver) {
        boolean removed = configManager.removeConfig(
                request.getNamespace(), request.getDataId(), request.getGroup(), request.getOperator());
        responseObserver.onNext(DeleteConfigResponse.newBuilder()
                .setSuccess(removed)
                .setMessage(removed ? "OK" : "Config not found")
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void listConfigHistory(ListConfigHistoryRequest request, StreamObserver<ListConfigHistoryResponse> responseObserver) {
        List<ConfigItem> history = configManager.listHistory(
                request.getNamespace(), request.getDataId(), request.getGroup(),
                request.getPage(), request.getPageSize() > 0 ? request.getPageSize() : 10);
        int total = configManager.getHistoryCount(request.getNamespace(), request.getDataId(), request.getGroup());

        ListConfigHistoryResponse.Builder builder = ListConfigHistoryResponse.newBuilder().setTotal(total);
        for (ConfigItem item : history) {
            builder.addRevisions(toProto(item));
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<WatchConfigRequest> watchConfig(StreamObserver<ConfigChangeEventProto> responseObserver) {
        return new StreamObserver<>() {
            private Consumer<ConfigItem> watcher;
            private String namespace;
            private String group;
            private String dataId;

            @Override
            public void onNext(WatchConfigRequest request) {
                if (watcher != null) {
                    watcherManager.removeWatcher(namespace, group, dataId, watcher);
                }

                this.namespace = request.getNamespace();
                this.group = request.getGroup();
                this.dataId = request.getDataId();

                this.watcher = item -> {
                    ConfigChangeEventProto event = ConfigChangeEventProto.newBuilder()
                            .setNamespace(item.getNamespace())
                            .setGroup(item.getGroup())
                            .setDataId(item.getDataId())
                            .setContent(item.getContent() != null ? item.getContent() : "")
                            .setMd5(item.getMd5() != null ? item.getMd5() : "")
                            .setVersion(item.getVersion())
                            .setChangeType(item.getVersion() == -1 ? ChangeType.DELETED : ChangeType.MODIFIED)
                            .build();
                    try {
                        responseObserver.onNext(event);
                    } catch (Exception e) {
                        log.warn("Failed to push config change to watcher", e);
                    }
                };

                watcherManager.addWatcher(namespace, group, dataId, watcher);
            }

            @Override
            public void onError(Throwable t) {
                cleanup();
            }

            @Override
            public void onCompleted() {
                cleanup();
                responseObserver.onCompleted();
            }

            private void cleanup() {
                if (watcher != null && dataId != null) {
                    watcherManager.removeWatcher(namespace, group, dataId, watcher);
                }
            }
        };
    }

    private ConfigItemProto toProto(ConfigItem item) {
        return ConfigItemProto.newBuilder()
                .setDataId(item.getDataId() != null ? item.getDataId() : "")
                .setGroup(item.getGroup() != null ? item.getGroup() : "")
                .setNamespace(item.getNamespace() != null ? item.getNamespace() : "")
                .setContent(item.getContent() != null ? item.getContent() : "")
                .setContentType(item.getContentType() != null ? item.getContentType() : "")
                .setMd5(item.getMd5() != null ? item.getMd5() : "")
                .setVersion(item.getVersion())
                .setLastModified(item.getLastModified())
                .setOperator(item.getOperator() != null ? item.getOperator() : "")
                .setDescription(item.getDescription() != null ? item.getDescription() : "")
                .build();
    }
}
