package com.lux.backend.infrastructure;

import com.lux.backend.common.ApiException;
import com.lux.backend.common.JsonCodec;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagingBudgetTests {
    @Test
    void anOversizedEnvelopeCannotCommitAnAcceptedButUndeliverableOutboxRecord() {
        OutboxJpaRepository rows = mock(OutboxJpaRepository.class);
        EntityManager entities = mock(EntityManager.class);
        JsonCodec json = mock(JsonCodec.class);
        String owner = "00000000-0000-0000-0000-000000000001";
        String session = "00000000-0000-0000-0000-000000000010";
        String event = "00000000-0000-0000-0000-000000000100";
        CommandPayload.ContinueSession envelope = new CommandPayload.ContinueSession(2, event, "CONTINUE_SESSION",
                "2026-10-12T09:00:00Z", owner, session);
        when(rows.findByEventId(event)).thenReturn(Optional.empty());
        // 编码器是边界替身，精确模拟超过预算一个 UTF-8 字节，不分配整个业务对象树。
        when(json.write(envelope)).thenReturn("x".repeat(MessagingLimits.MAX_ENVELOPE_BYTES + 1));
        ApiException rejected = assertThrows(ApiException.class,
                () -> new OutboxRepository(rows, entities, json).enqueueEnvelope(Topics.AGENT_COMMANDS, owner + ":" + session, envelope));
        assertTrue(rejected.getMessage().contains("transport budget"));
        verify(entities, never()).persist(any());
        assertTrue(MessagingLimits.MAX_RECORD_BYTES > MessagingLimits.MAX_ENVELOPE_BYTES);
    }

    @Test
    void v2TopicsUseTheSameRecordCapacityAsBothClientImplementations() {
        org.springframework.kafka.core.KafkaAdmin.NewTopics topics = new KafkaConfiguration().luxTopics();
        java.util.Collection<org.apache.kafka.clients.admin.NewTopic> declared =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(topics, "getNewTopics");
        assertNotNull(declared);
        for (org.apache.kafka.clients.admin.NewTopic topic : declared) {
            if (!topic.name().equals(Topics.LEGACY_GENERATION_EVENTS)) {
                assertNotNull(topic.configs(), "New v2 topics require an explicit record capacity");
                assertEquals(Integer.toString(MessagingLimits.MAX_RECORD_BYTES),
                        topic.configs().get(org.apache.kafka.common.config.TopicConfig.MAX_MESSAGE_BYTES_CONFIG));
            }
        }
    }
}
