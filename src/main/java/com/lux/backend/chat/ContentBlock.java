package com.lux.backend.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/** Text 和 Asset 共用的有序内容块；不存块身份，也不混入任务通知。 */
public sealed interface ContentBlock permits ContentBlock.Text, ContentBlock.Asset {
    @JsonProperty("type")
    String type();
    /** 原始 JSON 只在入口使用；进入业务前立即变成明确字段。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static ContentBlock decode(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Content block must be an object");
        }
        String type = string(value, "type");
        if (value.size() != 2) {
            throw new IllegalArgumentException("Unexpected content block field");
        }
        return switch (type) {
            case "text" -> new Text(string(value, "text"));
            case "asset" -> new Asset(string(value, "asset_id"));
            default -> throw new IllegalArgumentException("Unsupported content block");
        };
    }

    private static String string(JsonNode value, String field) {
        if (!value.path(field).isString()) {
            throw new IllegalArgumentException("Invalid content field: " + field);
        }
        return value.path(field).asString();
    }

    record Text(String text) implements ContentBlock {
        @Override
        @JsonProperty("type")
        public String type() {
            return "text";
        }
    }

    record Asset(String assetId) implements ContentBlock {
        @Override
        @JsonProperty("type")
        public String type() {
            return "asset";
        }
    }
}
