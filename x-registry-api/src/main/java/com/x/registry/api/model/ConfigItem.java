package com.x.registry.api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public class ConfigItem implements Serializable {

    private String dataId;
    private String group = "DEFAULT_GROUP";
    private String namespace = "public";
    private String content;
    private String contentType = "text";
    private String md5;
    private long version;
    private long lastModified;
    private String operator;
    private String description;

    public ConfigItem() {
    }

    public ConfigItem(String namespace, String group, String dataId) {
        this.namespace = namespace;
        this.group = group;
        this.dataId = dataId;
    }

    @JsonIgnore
    public String getKey() {
        return namespace + "@@" + group + "@@" + dataId;
    }

    public String computeMd5() {
        if (content == null || content.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            this.md5 = sb.toString();
            return this.md5;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        computeMd5();
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigItem that = (ConfigItem) o;
        return Objects.equals(dataId, that.dataId)
                && Objects.equals(group, that.group)
                && Objects.equals(namespace, that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataId, group, namespace);
    }
}
