package com.x.registry.client.event;

import com.x.registry.api.model.ConfigItem;

public record ConfigChangeEvent(String namespace, String group, String dataId, ConfigItem config) {
}
