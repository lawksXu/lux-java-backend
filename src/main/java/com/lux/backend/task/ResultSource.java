package com.lux.backend.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 供应商结果来源的显式结构；未知元数据原样保存在 JSON 边界，导入只读取命名字段。 */
public final class ResultSource {
    /** 仅供持久化无损往返，避免迁移时删除连接器携带的未知来源元数据。 */
    private final JsonNode wire;
    /** 完整下载 URL、显示文件名与具体媒体类型，由业务校验器进一步验证。 */
    private final String url, filename, mediaType;

    /** 构建后端已知来源结构，不引入额外的 JSON 字段。 */
    public ResultSource(String url, String filename, String mediaType) {
        this(JsonNodeFactory.instance.objectNode().put("url", url).put("filename", filename).put("media_type", mediaType),
            url, filename, mediaType);
    }

    private ResultSource(JsonNode wire, String url, String filename, String mediaType) {
        this.wire = wire.deepCopy(); this.url = url; this.filename = filename; this.mediaType = mediaType;
    }

    /** 持久化适配器保留历史标量文件名的文本语义；旧版本允许数字/布尔文件名，新入站另做严格校验。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ResultSource decode(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Result source must be an object");
        for (String field : new String[]{"url", "media_type"}) {
            if (!node.path(field).isString()) throw new IllegalArgumentException("Invalid result source field: " + field);
        }
        JsonNode filename = node.path("filename");
        if (!filename.isString() && !filename.isNumber() && !filename.isBoolean())
            throw new IllegalArgumentException("Invalid result source field: filename");
        return new ResultSource(node, node.path("url").asString(), filename.asText(), node.path("media_type").asString());
    }

    /** 下载器使用已投影的完整结果地址。 */
    public String url() { return url; }
    /** 元数据使用文件名文本；历史标量已按旧语义转换。 */
    public String filename() { return filename; }
    /** 资源元数据使用具体媒体类型。 */
    public String mediaType() { return mediaType; }
    /** 仅供 HTTP/数据库序列化，不作为业务字段读取接口。 */
    @JsonValue public JsonNode wire() { return wire.deepCopy(); }
    @Override public boolean equals(Object other) { return other instanceof ResultSource source && wire.equals(source.wire); }
    @Override public int hashCode() { return wire.hashCode(); }
}
