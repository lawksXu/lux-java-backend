package com.lux.backend.task;

import com.lux.backend.common.ApiException;
import com.lux.backend.common.JsonCodec;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskRepositoryTests {
    private final TaskJpaRepository rows = mock(TaskJpaRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final TaskRepository repository = new TaskRepository(rows, entities, new JsonCodec(JsonMapper.builder().build()));
    @Test void batchUsesOneOwnerScopedQueryAndPreservesRepeatedIds() {
        List<String> ids = List.of("second", "first", "second");
        when(rows.findByUserIdAndIdIn("owner", ids)).thenReturn(List.of(task("first"), task("second")));
        assertEquals(ids, repository.batch("owner", ids).stream().map(TaskRecord::id).toList());
        verify(rows).findByUserIdAndIdIn("owner", ids);
    }
    @Test void batchRejectsAnyOtherOwnerResource() {
        when(rows.findByUserIdAndIdIn("owner", List.of("first", "missing"))).thenReturn(List.of(task("first")));
        assertEquals(404, assertThrows(ApiException.class, () -> repository.batch("owner", List.of("first", "missing"))).status());
    }
    @Test void mutationLocksAndRefreshesTheCurrentState() {
        TaskEntity row = task("task");
        when(rows.findByUserIdAndId("owner", "task")).thenReturn(Optional.of(row));
        doAnswer(call -> { row.status = "IMPORTING"; row.version = 4L; return null; }).when(entities).refresh(row, LockModeType.PESSIMISTIC_WRITE);
        TaskRecord current = repository.getForMutation("owner", "task");
        assertEquals("IMPORTING", current.status());
        assertEquals(4, current.version());
    }
    @Test void terminalFailureIsImmutableAndHasNoRetrySchedule() {
        TaskEntity row = task("task");
        when(rows.findByUserIdAndId("owner", "task")).thenReturn(Optional.of(row));
        assertTrue(repository.finish("owner", "task", "FAILED", "IMPORT", "SOURCE", "original"));
        Instant finished = row.finishedAt;
        assertFalse(repository.finish("owner", "task", "SUCCEEDED", null, null, null));
        assertEquals("FAILED", row.status);
        assertEquals("original", row.errorMessage);
        assertEquals(finished, row.finishedAt);
        verify(entities, times(1)).flush();
    }
    private TaskEntity task(String id) {
        TaskEntity row = new TaskEntity(); row.id=id; row.userId="owner"; row.sessionId="session";
        row.operationId="operation"; row.fileType="IMAGE"; row.payload="{}"; row.status="RUNNING";
        row.createdAt=Instant.EPOCH; row.updatedAt=Instant.EPOCH; return row;
    }
}
