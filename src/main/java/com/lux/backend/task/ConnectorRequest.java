package com.lux.backend.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.lux.backend.generation.GenerationPlanCodec;
import com.lux.backend.generation.GenerationPlanContract.*;

/** 连接器参数适配器：供应商参数保持不透明，仅投影 Java 必须检查归属的资源引用。 */
public final class ConnectorRequest {
    /** 仅用于这个 JSON 边界的线程安全协议编解码器，避免逐个请求重复加载模式。 */
    private static final GenerationPlanCodec CODEC = new GenerationPlanCodec();
    private static final tools.jackson.databind.json.JsonMapper WIRE = tools.jackson.databind.json.JsonMapper.builder().build();
    /** 参数模式由各连接器拥有，保留原始 JSON 以维持转发和历史请求摘要。 */
    private final JsonNode parameters;
    /** 任意嵌套位置的 asset_id/asset_ids 引用，按原顺序保存以沿用校验语义。 */
    private final List<String> assetIds;
    /** 新规范化请求由 Java 拥有完整结构，不能继续作为不透明参数处理。 */
    private final NormalizedGenerationRequest normalized;

    private ConnectorRequest(JsonNode parameters, List<String> assetIds) {
        this.parameters = parameters.deepCopy(); this.assetIds = List.copyOf(assetIds); this.normalized = null;
    }

    private ConnectorRequest(NormalizedGenerationRequest request) {
        this.normalized = request; this.parameters = null;
        List<ReferenceAsset> references = request instanceof ImageGenerationRequest image
            ? image.parameters().references() : ((VideoGenerationRequest) request).parameters().references();
        this.assetIds = references.stream().map(reference -> reference.assetId().toString()).toList();
    }

    /** 事务内接受已验证的具名请求，不把批次数量传给供应商。 */
    public static ConnectorRequest normalized(NormalizedGenerationRequest request) { return new ConnectorRequest(request); }
    public NormalizedGenerationRequest normalized() { return normalized; }

    /** 在输入/持久化边界解析资源引用，业务服务无需再递归访问字符串键。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ConnectorRequest decode(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) throw new IllegalArgumentException("Task request must be an object");
        if (parameters.has("request_type")) {
            // 识别到新版声明后必须严格解码；格式错误不能退回旧供应商参数通道。
            return normalized(CODEC.readNormalized(parameters));
        }
        List<String> references = new ArrayList<>();
        collect(parameters, references);
        return new ConnectorRequest(parameters, references);
    }

    /** 返回不可变引用列表；资源是否存在和属于当前用户由业务仓储确认。 */
    public List<String> assetIds() { return assetIds; }

    /** 输出原请求形状，不把适配器内部 assetIds 投影添加到供应商协议。 */
    @JsonValue public JsonNode wire() { return normalized == null ? parameters.deepCopy() : WIRE.readTree(CODEC.write(normalized)); }

    /** 对象键顺序不参与身份；数组顺序、null 和缺失字段仍保持区别。 */
    @Override public boolean equals(Object other) {
        return other instanceof ConnectorRequest request && java.util.Objects.equals(normalized, request.normalized)
            && java.util.Objects.equals(parameters, request.parameters);
    }
    @Override public int hashCode() { return java.util.Objects.hash(parameters, normalized); }

    /** 仅适配器解释保留字段，其他供应商字段原样转发。 */
    private static void collect(JsonNode node, List<String> references) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (field.getKey().equals("asset_id")) references.add(reference(field.getValue()));
                else if (field.getKey().equals("asset_ids")) {
                    if (!field.getValue().isArray()) throw new IllegalArgumentException("asset_ids must be an array");
                    for (JsonNode value : field.getValue()) references.add(reference(value));
                } else collect(field.getValue(), references);
            }
        } else if (node.isArray()) for (JsonNode value : node) collect(value, references);
    }

    private static String reference(JsonNode node) {
        if (!node.isString()) throw new IllegalArgumentException("Asset reference must be a string");
        return node.asString();
    }
}
