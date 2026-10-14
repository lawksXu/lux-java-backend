package com.lux.backend.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * JSON 编解码与请求摘要工具；规范化对象键顺序后计算 SHA-256，用于内容幂等比较。
 */
@Component
public class JsonCodec {
    /**
     * 应用注入的 Jackson 映射器，沿用统一命名策略和类型支持。
     */
    private final JsonMapper mapper;

    /**
     * 通过构造器集中接收所需依赖，使组件职责与协作对象明确可见。
     */
    public JsonCodec(JsonMapper mapper) {this.mapper = mapper;}

    /**
     * 把对象序列化为 JSON 字符串，供数据库 JSON 列或 Kafka 信封存储。
     */
    public String write(Object value) {return mapper.writeValueAsString(value);}

    /**
     * 把 JSON 字符串解析为可按字段访问的树。
     */
    public JsonNode read(String value) {return mapper.readTree(value);}

    /**
     * 在 HTTP 回执或数据库边界恢复调用者声明的结构。
     */
    public <T> T read(
        String value,
        Class<T> type
    ) {return mapper.readValue(value, type);}

    /**
     * 在数据库数组边界恢复显式元素类型，避免业务层再按字段读取树。
     */
    public <T> java.util.List<T> readList(
        String value,
        Class<T> type
    ) {
        return mapper.readValue(value, mapper.getTypeFactory().constructCollectionType(java.util.List.class, type));
    }

    /**
     * 把对象转换为 JSON 树，沿用应用统一的序列化配置。
     */
    public JsonNode tree(Object value) {return mapper.valueToTree(value);}

    /**
     * 创建空 JSON 对象节点，供事件信封和请求载荷组装。
     */
    public ObjectNode object() {return mapper.createObjectNode();}

    /**
     * 创建空 JSON 数组节点，供消息内容块和批次输入组装。
     */
    public ArrayNode array() {return mapper.createArrayNode();}

    /**
     * 规范化 JSON 对象键后计算 SHA-256，使仅键顺序不同的请求拥有相同摘要。
     */
    public String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                                         .digest(canonical(tree(value))
                                                                     .toString()
                                                                     .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 递归排序对象键但保留数组顺序，因为消息块和输入批次的顺序具有业务含义。
     */
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = object();
            // 只排序对象属性；数组顺序会影响业务语义，因此在下面的数组分支中保留。
            node.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> result.set(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = array();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }
}
