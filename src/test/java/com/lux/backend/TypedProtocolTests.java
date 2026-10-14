package com.lux.backend;

import com.lux.backend.chat.ContentBlock;
import com.lux.backend.chat.ConversationContent;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.infrastructure.EventProtocol;
import com.lux.backend.infrastructure.IncomingEvent;
import com.lux.backend.task.ConnectorRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypedProtocolTests {
    private static final Path FIXTURES = Path.of("docs/fixtures/v2");
    private final JsonCodec json = new JsonCodec(JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).build());
    private record FixtureDigest(String filename, String sha256) {}

    @Test
    void sharedOriginalEnvelopesHaveTheSameCanonicalDigestAsPython() throws Exception {
        for (FixtureDigest fixture : json.readList(Files.readString(FIXTURES.resolve("digests.json")), FixtureDigest.class)) {
            JsonNode original = json.read(Files.readString(FIXTURES.resolve(fixture.filename())));
            assertEquals(fixture.sha256(), json.hash(original), fixture.filename());
            if (fixture.filename().equals("input-accepted.json")) {
                com.lux.backend.infrastructure.CommandPayload.InputAccepted input = EventProtocol.input(original);
                assertEquals(json.hash(original), json.hash(input));
                assertEquals(input.eventId(), input.item().id());
                assertEquals("\n  画一只猫 😊\n", ((ContentBlock.Text)
                        ((ConversationContent.OrderedContent) input.item().content()).blocks().get(0)).text());
            } else {
                IncomingEvent.ExecutionFact fact = EventProtocol.agent(original);
                assertEquals(original, fact.originalEnvelope());
                assertEquals(json.hash(original.path("body")), json.hash(fact.body()));
                assertNull(original.get("run_id"));
            }
        }
    }

    @Test
    void sequenceOverflowAndScalarCoercionCannotBecomeExecutionFacts() throws Exception {
        ObjectNode delta = (ObjectNode) json.read(Files.readString(FIXTURES.resolve("item-delta.json")));
        delta.set("event_seq", json.read("9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> EventProtocol.agent(delta));
        delta.put("event_seq", 4);
        delta.withObject("body").put("stream_seq", true);
        assertThrows(IllegalArgumentException.class, () -> EventProtocol.agent(delta));
        delta.withObject("body").put("stream_seq", 1).put("text", 12);
        assertThrows(IllegalArgumentException.class, () -> EventProtocol.agent(delta));
    }

    @Test
    void initialPartialPayloadCannotBeNormalizedFromAnInvalidNonemptySnapshot() throws Exception {
        ObjectNode start = (ObjectNode) json.read(Files.readString(FIXTURES.resolve("item-start.json")));
        start.withObject("body").withObject("payload").set("blocks",
                json.read("[{\"type\":\"text\",\"text\":\"already populated\"}]"));
        assertThrows(IllegalArgumentException.class, () -> EventProtocol.agent(start));
    }

    @Test
    void taskNotificationHasOnlyItsIdAndCannotBecomeAUserToolResult() throws Exception {
        ObjectNode input = (ObjectNode) json.read(Files.readString(FIXTURES.resolve("input-accepted.json")));
        input.withObject("item").put("kind", "TASK_EVENT");
        input.withObject("item").set("content", json.read("{\"task_id\":\"00000000-0000-0000-0000-000000000700\"}"));
        assertThrows(IllegalArgumentException.class, () -> EventProtocol.input(input));
        input.withObject("item").put("source", "SYSTEM");
        assertInstanceOf(ConversationContent.TaskEventContent.class, EventProtocol.input(input).item().content());
        input.withObject("item").withObject("content").put("status", "SUCCEEDED");
        assertThrows(IllegalArgumentException.class, () -> EventProtocol.input(input));
    }

    @Test
    void connectorOwnedParametersRemainOpaqueButAssetReferencesAreTyped() {
        String id = "00000000-0000-0000-0000-000000000001";
        JsonNode original = json.read("{\"prompt\":\"cat\",\"nested\":[{\"asset_id\":\"" + id
                + "\"},{\"asset_ids\":[\"" + id + "\"]}],\"provider_specific\":{\"seed\":42}}");
        ConnectorRequest request = ConnectorRequest.decode(original);
        assertEquals(List.of(id, id), request.assetIds());
        assertEquals(original, json.tree(request));
        assertEquals(json.hash(original), json.hash(request));
        assertThrows(IllegalArgumentException.class, () -> ConnectorRequest.decode(json.read("{\"asset_ids\":null}")));
    }
}
