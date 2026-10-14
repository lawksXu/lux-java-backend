package com.lux.backend.chat;

import com.lux.backend.common.JsonCodec;
import com.lux.backend.infrastructure.EventProtocol;
import com.lux.backend.infrastructure.Topics;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationProtocolTests {
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());

    @Test
    void sessionExecutionFactDoesNotRequireAnOuterRunIdentity() {
        String wire = """
            {"schema_version":2,"event_id":"00000000-0000-0000-0000-000000000004",
             "event_type":"RUN_SNAPSHOT","occurred_at":"2026-10-12T00:00:00Z",
             "user_id":"00000000-0000-0000-0000-000000000001",
             "session_id":"00000000-0000-0000-0000-000000000002","event_seq":7,
             "body":{"run":{"id":"00000000-0000-0000-0000-000000000003","run_index":1},
             "source_revision":1,"status":"RUNNING","started_at":null,"finished_at":null,
             "final_item":null,"error_code":null,"error_message":null}}
            """;
        assertThatNoException().isThrownBy(() -> EventProtocol.validate(Topics.AGENT_EVENTS, json.read(wire)));
    }

    @Test void originalInputKeepsItsTimeAndIdentityWithoutExecutionBinding() throws Exception {
        String raw=java.nio.file.Files.readString(java.nio.file.Path.of("docs/fixtures/v2/input-accepted.json"));
        com.lux.backend.infrastructure.CommandPayload.InputAccepted input=EventProtocol.input(json.read(raw));
        assertThat(input.eventId()).isEqualTo(input.item().id());
        assertThat(input.occurredAt()).isEqualTo(json.read(raw).path("occurred_at").asString());
        assertThat(input.item().inputSequenceNo()).isEqualTo(1);
    }
    @Test void aNonemptyPartialSourceSnapshotCannotEraseOrReplaceTemporaryContent() throws Exception {
        tools.jackson.databind.node.ObjectNode value=(tools.jackson.databind.node.ObjectNode)json.read(java.nio.file.Files.readString(java.nio.file.Path.of("docs/fixtures/v2/item-start.json")));
        value.withObject("body").withObject("payload").set("blocks",json.read("[{\"type\":\"text\",\"text\":\"forged full partial\"}]"));
        assertThatThrownBy(()->EventProtocol.agent(value)).hasMessageContaining("empty content");
    }
    @Test void textDeltaPreservesRepeatedWhitespaceAsIndependentFragments() throws Exception {
        tools.jackson.databind.node.ObjectNode value=(tools.jackson.databind.node.ObjectNode)json.read(java.nio.file.Files.readString(java.nio.file.Path.of("docs/fixtures/v2/item-delta.json")));
        value.withObject("body").put("text","  \n");
        com.lux.backend.infrastructure.IncomingEvent.ItemTextDelta delta=(com.lux.backend.infrastructure.IncomingEvent.ItemTextDelta)EventProtocol.agent(value).body();
        assertThat(delta.text()).isEqualTo("  \n");
        assertThat(delta.streamSeq()).isEqualTo(1);
    }

    @Test void removedOuterExecutionAndInputFieldsAreRejectedInsteadOfDropped() throws Exception {
        for(String field:java.util.List.of("run_id","turn_id","role")) {
            tools.jackson.databind.node.ObjectNode fact=fixture("run-snapshot");fact.put(field,"removed");
            assertThatThrownBy(()->EventProtocol.agent(fact)).isInstanceOf(IllegalArgumentException.class);
            tools.jackson.databind.node.ObjectNode input=fixture("input-accepted");input.put(field,"removed");
            assertThatThrownBy(()->EventProtocol.input(input)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void removedBlockAndSourceSnapshotFieldsAreRejected() throws Exception {
        tools.jackson.databind.node.ObjectNode input=fixture("input-accepted");
        ((tools.jackson.databind.node.ObjectNode)input.path("item").path("content").path("blocks").get(0)).put("id","old-block");
        assertThatThrownBy(()->EventProtocol.input(input)).isInstanceOf(IllegalArgumentException.class);
        for(String field:java.util.List.of("source_hash","last_included_delta_sequence_no","role")) {
            tools.jackson.databind.node.ObjectNode fact=fixture("item-start");fact.withObject("body").put(field,"removed");
            assertThatThrownBy(()->EventProtocol.agent(fact)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void unknownTopicAndOverflowingGenerationVersionAreProtocolErrors() throws Exception {
        assertThatThrownBy(()->EventProtocol.validate("unknown-topic",fixture("run-snapshot"))).isInstanceOf(IllegalArgumentException.class);
        tools.jackson.databind.node.ObjectNode generation=(tools.jackson.databind.node.ObjectNode)json.read("{\"schema_version\":4294967298,\"event_id\":\"00000000-0000-0000-0000-000000000001\",\"occurred_at\":\"2026-10-12T00:00:00Z\",\"user_id\":\"00000000-0000-0000-0000-000000000001\",\"session_id\":\"00000000-0000-0000-0000-000000000002\",\"task_id\":\"00000000-0000-0000-0000-000000000003\",\"sequence_no\":1,\"connector_status\":\"RUNNING\",\"outcome\":null}");
        assertThatThrownBy(()->EventProtocol.generation(generation)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void taskQueryAssetsHaveDistinctIdentitiesAndStrictlyIncreasingOriginalPositions() {
        String first = "00000000-0000-0000-0000-000000000001";
        String second = "00000000-0000-0000-0000-000000000002";
        for (String references : java.util.List.of(
            "[{\"asset_id\":\"" + first + "\",\"source_index\":0},{\"asset_id\":\"" + first + "\",\"source_index\":1}]",
            "[{\"asset_id\":\"" + first + "\",\"source_index\":0},{\"asset_id\":\"" + second + "\",\"source_index\":0}]",
            "[{\"asset_id\":\"" + first + "\",\"source_index\":3},{\"asset_id\":\"" + second + "\",\"source_index\":1}]")) {
            String payload = "{\"type\":\"task_query_snapshot\",\"task_id\":\"" + first
                + "\",\"version\":1,\"status\":\"IMPORTING\",\"assets\":" + references + ",\"failure\":null}";
            assertThatThrownBy(() -> EventProtocol.payload("TOOL_RESULT", null, json.read(payload)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void persistedFailureMessagesCannotExceedTheUtf8TextColumnCapacity() throws Exception {
        String oversized = "中".repeat(21846);
        tools.jackson.databind.node.ObjectNode run = fixture("run-snapshot");
        run.withObject("body").put("error_message", oversized);
        assertThatThrownBy(() -> EventProtocol.agent(run)).isInstanceOf(IllegalArgumentException.class);

        tools.jackson.databind.node.ObjectNode generation = (tools.jackson.databind.node.ObjectNode) json.read("""
            {"schema_version":2,"event_id":"00000000-0000-0000-0000-000000000001",
             "occurred_at":"2026-10-12T00:00:00Z","user_id":"00000000-0000-0000-0000-000000000001",
             "session_id":"00000000-0000-0000-0000-000000000002","task_id":"00000000-0000-0000-0000-000000000003",
             "sequence_no":1,"connector_status":"FAILED","outcome":{"status":"FAILED",
             "error":{"code":"PROVIDER_FAILURE","stage":"PROVIDER","message":"error","provider_diagnostic":null}}}
            """);
        generation.withObject("outcome").withObject("error").put("message", oversized);
        assertThatThrownBy(() -> EventProtocol.generation(generation)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factTimeRejectsNonzeroSubMicrosecondsAndKeepsPermittedOriginalZeros() throws Exception {
        tools.jackson.databind.node.ObjectNode fact = fixture("run-snapshot");
        fact.put("occurred_at", "2026-10-12T00:00:00.123456001Z");
        assertThatThrownBy(() -> EventProtocol.agent(fact)).isInstanceOf(IllegalArgumentException.class);
        fact.put("occurred_at", "2026-10-12T00:00:00.123456000Z");
        assertThat(EventProtocol.agent(fact).header().occurredAt())
            .isEqualTo("2026-10-12T00:00:00.123456000Z");
    }
    private tools.jackson.databind.node.ObjectNode fixture(String name) throws Exception {
        return (tools.jackson.databind.node.ObjectNode)json.read(java.nio.file.Files.readString(java.nio.file.Path.of("docs/fixtures/v2/"+name+".json")));
    }
}
