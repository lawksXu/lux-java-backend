package com.lux.backend.generation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 仅在协议入口使用 JSON 树验证结构，随后转换为显式记录；业务逻辑不读取树字段。 */
@Component
public final class GenerationPlanCodec {
    /** 统一输出微秒精度 UTC 时间，同时让默认 Instant 解码器接受合同的零到六位小数。 */
    public static final class MicrosecondInstantSerializer extends tools.jackson.databind.ValueSerializer<Instant> {
        @Override public void serialize(Instant value, tools.jackson.core.JsonGenerator generator,
                                        tools.jackson.databind.SerializationContext context) {
            if (value.getNano() % 1000 != 0) throw new IllegalArgumentException("Timestamp exceeds microsecond precision");
            generator.writeString(new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value));
        }
    }
    private final JsonNode definitions;
    private final JsonMapper mapper;

    /** 加载与设计文档完全一致的模式，并为每个封闭联合安装严格解码器。 */
    public GenerationPlanCodec() {
        JsonMapper initial = JsonMapper.builder().build();
        try (InputStream stream = GenerationPlanCodec.class.getResourceAsStream("/generation-plan-v1/contract.schema.json")) {
            if (stream == null) throw new IllegalStateException("Generation plan schema missing");
            definitions = initial.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).get("$defs");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load generation plan schema", exception);
        }
        SimpleModule module = new SimpleModule("generation-plan-v1");
        for (Class<?> type : GenerationPlanContract.class.getDeclaredClasses()) {
            if (type.isSealed()) registerUnion(module, type);
        }
        mapper = initial.rebuild().addModule(module).build();
    }

    /** 根据模式命名解码，不允许缺少可空字段或出现额外字段。 */
    public <T> T read(String value, Class<T> type) { return read(mapper.readTree(value), type); }
    public <T> T read(JsonNode value, Class<T> type) {
        validate(value, definitions.get(type.getSimpleName()), "$", true);
        return mapper.treeToValue(value, type);
    }
    public String write(Object value) { return mapper.writeValueAsString(value); }
    public JavaPlanToolInvocation decodeInvocation(String value) { return read(value, JavaPlanToolInvocation.class); }
    public JavaPlanToolInvocation decodeInvocation(JsonNode value) { return read(value, JavaPlanToolInvocation.class); }
    public GenerationPlan decodePlan(String value) { return read(value, GenerationPlan.class); }
    public GenerationPlanToolResult decodeToolResult(JsonNode value) { return read(value, GenerationPlanToolResult.class); }
    public GenerationPlanToolResult decodeToolResult(String value) { return read(value, GenerationPlanToolResult.class); }
    public NormalizedGenerationRequest readNormalized(JsonNode value) { return read(value, NormalizedGenerationRequest.class); }
    public NormalizedGenerationRequest readNormalized(String value) { return read(value, NormalizedGenerationRequest.class); }

    private <T> void registerUnion(SimpleModule module, Class<T> type) {
        module.addDeserializer(type, new ValueDeserializer<T>() {
            @Override public T deserialize(JsonParser parser, DeserializationContext context) {
                JsonNode value = context.readTree(parser);
                JsonNode schema = definitions.get(type.getSimpleName());
                Class<?> matched = null;
                for (JsonNode candidate : schema.get("oneOf")) {
                    String name = candidate.get("$ref").asString().substring("#/$defs/".length());
                    if (validate(value, definitions.get(name), "$", false)) {
                        if (matched != null) throw new IllegalArgumentException("Ambiguous " + type.getSimpleName());
                        try { matched = Class.forName(GenerationPlanContract.class.getName() + "$" + name); }
                        catch (ClassNotFoundException exception) { throw new IllegalStateException(exception); }
                    }
                }
                if (matched == null) throw new IllegalArgumentException("Invalid " + type.getSimpleName());
                return type.cast(context.readTreeAsValue(value, matched));
            }
        });
    }

    /** 此验证器只执行本合同使用的模式关键字；不充当可扩展通用 JSON Schema 框架。 */
    private boolean validate(JsonNode value, JsonNode schema, String path, boolean throwing) {
        try {
            check(value, schema, path);
            return true;
        } catch (IllegalArgumentException exception) {
            if (throwing) throw exception;
            return false;
        }
    }
    private void check(JsonNode value, JsonNode schema, String path) {
        if (schema == null) throw new IllegalArgumentException("Unknown contract type at " + path);
        if (schema.has("$ref")) { check(value, definitions.get(schema.get("$ref").asString().substring(8)), path); return; }
        if (schema.has("anyOf") || schema.has("oneOf")) {
            JsonNode choices = schema.has("oneOf") ? schema.get("oneOf") : schema.get("anyOf");
            int matches = 0;
            for (JsonNode choice : choices) if (validate(value, choice, path, false)) matches++;
            require(matches > 0 && (!schema.has("oneOf") || matches == 1), path);
        }
        if (schema.has("const")) require(schema.get("const").equals(value), path);
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) if (option.equals(value)) found = true;
            require(found, path);
        }
        if (schema.has("type")) {
            String kind = schema.get("type").asString();
            switch (kind) {
                case "null" -> require(value.isNull(), path);
                case "boolean" -> require(value.isBoolean(), path);
                case "integer" -> {
                    require(value.isIntegralNumber() && value.canConvertToLong(), path);
                    long number = value.asLong();
                    if (schema.has("minimum")) require(number >= schema.get("minimum").asLong(), path);
                    if (schema.has("maximum")) require(number <= schema.get("maximum").asLong(), path);
                }
                case "string" -> {
                    require(value.isString(), path);
                    String text = value.asString();
                    int length = text.codePointCount(0, text.length());
                    if (schema.has("minLength")) require(length >= schema.get("minLength").asInt() && !text.isBlank(), path);
                    if (schema.has("maxLength")) require(length <= schema.get("maxLength").asInt(), path);
                    if (schema.has("pattern")) require(java.util.regex.Pattern.compile(schema.get("pattern").asString()).matcher(text).find(), path);
                    if (schema.has("format") && "date-time".equals(schema.get("format").asString())) {
                        try { Instant.parse(text); } catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid timestamp at " + path); }
                    }
                    if (schema.path("maxLength").asInt() == 64000) require(text.getBytes(StandardCharsets.UTF_8).length <= 262144, path);
                }
                case "array" -> {
                    require(value.isArray(), path);
                    if (schema.has("minItems")) require(value.size() >= schema.get("minItems").asInt(), path);
                    if (schema.has("maxItems")) require(value.size() <= schema.get("maxItems").asInt(), path);
                    for (int index = 0; index < value.size(); index++) check(value.get(index), schema.get("items"), path + "[" + index + "]");
                }
                case "object" -> {
                    require(value.isObject(), path);
                    JsonNode properties = schema.get("properties");
                    if (schema.has("required")) for (JsonNode field : schema.get("required")) require(value.has(field.asString()), path + "." + field.asString());
                    for (Map.Entry<String, JsonNode> entry : value.properties()) {
                        if (schema.path("additionalProperties").isBoolean() && !schema.get("additionalProperties").asBoolean()) require(properties.has(entry.getKey()), path + "." + entry.getKey());
                        if (properties.has(entry.getKey())) check(entry.getValue(), properties.get(entry.getKey()), path + "." + entry.getKey());
                    }
                }
                default -> throw new IllegalStateException("Unsupported schema keyword " + kind);
            }
        }
        if (schema.has("allOf")) for (JsonNode clause : schema.get("allOf")) {
            if (clause.has("if")) check(value, validate(value, clause.get("if"), path, false) ? clause.get("then") : clause.get("else"), path);
            else check(value, clause, path);
        }
        // 条件子模式只声明 properties；必须独立检查它，不能依赖 type=object。
        if (!schema.has("type") && schema.has("properties")) for (Map.Entry<String, JsonNode> entry : schema.get("properties").properties()) {
            if (value.has(entry.getKey())) check(value.get(entry.getKey()), entry.getValue(), path + "." + entry.getKey());
        }
    }
    private static void require(boolean valid, String path) { if (!valid) throw new IllegalArgumentException("Invalid generation plan contract at " + path); }
}
