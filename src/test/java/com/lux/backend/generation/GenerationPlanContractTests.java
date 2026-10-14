package com.lux.backend.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.lux.backend.generation.GenerationPlanContract.*;

class GenerationPlanContractTests {
    private final GenerationPlanCodec codec = new GenerationPlanCodec();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void everyNamedContractDefinitionHasExplicitTypeAndStrictRoundTrip() throws Exception {
        JsonNode definitions = mapper.readTree(Files.readString(Path.of("docs/fixtures/generation-plan-v1/contract.schema.json"))).get("$defs");
        for (java.util.Map.Entry<String, JsonNode> definition : definitions.properties()) {
            String name = definition.getKey();
            if (java.util.Set.of("Uuid", "Instant", "Sha256").contains(name)) continue;
            Class<?> type = Class.forName(GenerationPlanContract.class.getName() + "$" + name);
            JsonNode value = sample(definition.getValue(), definitions);
            Object decoded = codec.read(value, type);
            assertEquals(mapper.readTree(value.toString()), mapper.readTree(codec.write(decoded)), name);
            if (value instanceof ObjectNode object) {
                ObjectNode extra = object.deepCopy(); extra.put("unknown_field", false);
                assertThrows(IllegalArgumentException.class, () -> codec.read(extra, type), name);
                JsonNode concrete = definition.getValue();
                if (concrete.has("required")) for (JsonNode field : concrete.get("required")) {
                    ObjectNode missing = object.deepCopy(); missing.remove(field.asString());
                    assertThrows(IllegalArgumentException.class, () -> codec.read(missing, type), name + "." + field.asString());
                }
            }
        }
    }

    private JsonNode sample(JsonNode schema, JsonNode definitions) {
        if (schema.has("$ref")) return sample(definitions.get(schema.get("$ref").asString().substring(8)), definitions);
        if (schema.has("const")) return schema.get("const");
        if (schema.has("enum")) return schema.get("enum").get(0);
        if (schema.has("anyOf")) return mapper.getNodeFactory().nullNode();
        if (schema.has("oneOf")) return sample(schema.get("oneOf").get(0), definitions);
        return switch (schema.get("type").asString()) {
            case "object" -> {
                ObjectNode object = mapper.createObjectNode();
                for (java.util.Map.Entry<String, JsonNode> field : schema.get("properties").properties()) object.set(field.getKey(), sample(field.getValue(), definitions));
                yield object;
            }
            case "array" -> {
                tools.jackson.databind.node.ArrayNode array = mapper.createArrayNode();
                for (int index = 0; index < schema.path("minItems").asInt(); index++) array.add(sample(schema.get("items"), definitions));
                yield array;
            }
            case "string" -> {
                String pattern = schema.path("pattern").asString();
                yield mapper.getNodeFactory().stringNode(schema.has("format") ? "2026-10-13T00:00:00.000000Z" : pattern.contains("{64}") ? "0".repeat(64) : schema.has("pattern") ? "00000000-0000-0000-0000-000000000001" : "test");
            }
            case "integer" -> mapper.getNodeFactory().numberNode(schema.path("minimum").asLong());
            case "boolean" -> mapper.getNodeFactory().booleanNode(false);
            case "null" -> mapper.getNodeFactory().nullNode();
            default -> throw new AssertionError("Unknown synthetic schema branch");
        };
    }

    @Test
    void structuralFixturesRoundTripWithFalseNullAndOrderedReferences() throws Exception {
        JsonNode examples = mapper.readTree(Files.readString(Path.of("docs/fixtures/generation-plan-v1/examples.json")));
        for (JsonNode example : examples) {
            JsonNode value = example.get("value");
            Class<?> type = switch (value.path("type").asString()) {
                case "model_catalog_snapshot" -> ModelCatalogSnapshot.class;
                case "generation_plan_tool_invocation" -> JavaPlanToolInvocation.class;
                case "generation_plan" -> GenerationPlan.class;
                case "generation_plan_tool_operation_receipt" -> JavaPlanToolOperationReceipt.class;
                default -> NormalizedGenerationRequest.class;
            };
            Object decoded = codec.read(value, type);
            assertEquals(value, mapper.readTree(codec.write(decoded)), example.get("name").asString());
            assertEquals(decoded, codec.read(codec.write(decoded), type));
        }
    }

    @Test
    void runtimeSchemaMustRemainExactAuthoritativeCopy() throws Exception {
        assertArrayEquals(Files.readAllBytes(Path.of("docs/fixtures/generation-plan-v1/contract.schema.json")),
                          getClass().getResourceAsStream("/generation-plan-v1/contract.schema.json").readAllBytes());
    }


    @Test
    void providerBindingRejectsBlankEachFieldAndPreservesExactNonblankValues() {
        for (String blank : java.util.List.of("", " ", "\t\n\r", "\u0085", "\u00a0", "\u2003", "\u3000", " \u00a0\t\u2003\n")) {
            assertThrows(IllegalArgumentException.class, () -> new ProviderBinding(blank, "model", "adapter"));
            assertThrows(IllegalArgumentException.class, () -> new ProviderBinding("provider", blank, "adapter"));
            assertThrows(IllegalArgumentException.class, () -> new ProviderBinding("provider", "model", blank));
            for (String field : java.util.List.of("provider_id", "provider_model_id", "adapter_id")) {
                ObjectNode json = mapper.createObjectNode().put("provider_id", "provider").put("provider_model_id", "model").put("adapter_id", "adapter");
                json.put(field, blank);
                assertThrows(IllegalArgumentException.class, () -> codec.read(json, ProviderBinding.class), field);
            }
        }
        ProviderBinding exact = new ProviderBinding(" \u00a0provider\n", "\tmodel ", "adapter\u3000");
        assertEquals(" \u00a0provider\n", exact.providerId());
        assertEquals("\tmodel ", exact.providerModelId());
        assertEquals("adapter\u3000", exact.adapterId());
        assertEquals(exact, codec.read(codec.write(exact), ProviderBinding.class));
    }


    @Test
    void providerBindingLengthLimitCountsUnicodeCodePointsRatherThanUtf16Units() {
        String nonBmp = "\ud83d\ude80";
        ProviderBinding maximum = new ProviderBinding(nonBmp.repeat(64), "model", "adapter");
        assertEquals(128, maximum.providerId().length());
        assertEquals(maximum, codec.read(codec.write(maximum), ProviderBinding.class));
        ProviderBinding oversized = new ProviderBinding(nonBmp.repeat(65), "model", "adapter");
        assertThrows(IllegalArgumentException.class, () -> codec.read(codec.write(oversized), ProviderBinding.class));
    }

    @Test
    void sharedSchemaBindingPatternsRejectUnicodeWhitespaceWithoutTrimmingValues() throws Exception {
        JsonNode properties = mapper.readTree(Files.readString(Path.of("docs/fixtures/generation-plan-v1/contract.schema.json"))).get("$defs").get("ProviderBinding").get("properties");
        for (java.util.Map.Entry<String, JsonNode> field : properties.properties()) {
            assertNotNull(field.getValue().get("pattern"), field.getKey());
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(field.getValue().get("pattern").asString());
            for (String blank : java.util.List.of(" ", "\t\n", "\u0085", "\u00a0", "\u2003", "\u3000")) assertFalse(pattern.matcher(blank).find(), field.getKey());
            assertTrue(pattern.matcher(" \u00a0real-id\n").find(), field.getKey());
        }
    }

    @Test
    void missingRequiredNullableExtrasAndWrongPrimitiveKindsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.read("{}", GetModelCatalogArguments.class));
        assertThrows(IllegalArgumentException.class, () -> codec.read("{\"media_type\":null,\"extra\":false}", GetModelCatalogArguments.class));
        assertThrows(IllegalArgumentException.class, () -> codec.read("{\"width_units\":1.0,\"height_units\":1}", AspectRatio.class));
        assertThrows(IllegalArgumentException.class, () -> codec.read("{\"provider_id\":\" \",\"provider_model_id\":\"x\",\"adapter_id\":\"v1\"}", ProviderBinding.class));
    }

    @Test
    void conditionalFrozenAndOriginConstraintsAreEnforced() throws Exception {
        JsonNode examples = mapper.readTree(Files.readString(Path.of("docs/fixtures/generation-plan-v1/examples.json")));
        ObjectNode plan = (ObjectNode) examples.get(9).get("value").deepCopy();
        plan.put("state", "FROZEN");
        assertThrows(IllegalArgumentException.class, () -> codec.read(plan, GenerationPlan.class));
        assertThrows(IllegalArgumentException.class, () -> codec.read("{\"field\":\"PROMPT\",\"source\":\"MODEL_DEFAULT\",\"source_model\":null}", ParameterOrigin.class));
    }
}
