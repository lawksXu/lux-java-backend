package com.lux.backend.infrastructure;

import com.lux.backend.chat.RunEventService;
import com.lux.backend.common.JsonCodec;
import com.lux.backend.task.TaskResultService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeBoundaryTests {
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());

    @Test
    void newAgentMessagesCannotBeConsumedFromTheLegacyProtocolTopics() {
        assertEquals("lux.agent.commands.v2", Topics.AGENT_COMMANDS);
        assertEquals("lux.agent.events.v2", Topics.AGENT_EVENTS);
    }

    @Test
    void genericInboxRecoveryCannotLaunchAGenerationImportOutsideKafkaConsumption() {
        InboxRepository inbox = mock(InboxRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        String event = "00000000-0000-0000-0000-000000000001";
        when(inbox.pending()).thenReturn(List.of(new InboxRepository.Key(Topics.GENERATION_EVENTS, event)));
        InboxProcessor processor = new InboxProcessor(inbox, mock(RunEventService.class),
                mock(TaskResultService.class), transactions, json);

        processor.drain();

        verify(transactions, never()).execute(any());
        verify(inbox, never()).forProcessing(Topics.GENERATION_EVENTS, event);
    }

    @Test
    void unfinishedGenerationProcessingDoesNotReturnSuccessToTheKafkaListener() {
        InboxRepository inbox = mock(InboxRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doThrow(new IllegalStateException("Database unavailable"))
                .when(transactions).execute(any());
        InboxProcessor processor = new InboxProcessor(inbox, mock(RunEventService.class),
                mock(TaskResultService.class), transactions, json);

        assertThrows(IllegalStateException.class,
                () -> processor.process(Topics.GENERATION_EVENTS, "00000000-0000-0000-0000-000000000001"));

        verify(inbox, never()).applied(anyString(), anyString());
    }
}
