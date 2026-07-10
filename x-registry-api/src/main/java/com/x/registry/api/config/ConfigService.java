package com.x.registry.api.config;

import com.x.registry.api.model.ConfigItem;

import java.util.List;
import java.util.function.Consumer;

public interface ConfigService {

    ConfigItem getConfig(String namespace, String dataId, String group);

    boolean publishConfig(String namespace, String dataId, String group, String content,
                          String contentType, String operator, String description);

    boolean removeConfig(String namespace, String dataId, String group, String operator);

    List<ConfigItem> listHistory(String namespace, String dataId, String group, int page, int pageSize);

    void addListener(String namespace, String dataId, String group, Consumer<ConfigItem> listener);

    void removeListener(String namespace, String dataId, String group, Consumer<ConfigItem> listener);
}
