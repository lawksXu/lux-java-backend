package com.lux.backend.generation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.lux.backend.generation.GenerationPlanContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 无数据库时验证仓储边界的锁重读及冻结保护；不代替 MySQL 集成验证。 */
class GenerationPlanRepositoryTests {
    private final GenerationPlanJpaRepository rows = mock(GenerationPlanJpaRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final GenerationPlanCodec codec = new GenerationPlanCodec();
    private final GenerationPlanRepository repository = new GenerationPlanRepository(rows, entities, codec);

    @Test
    void lockReadsTheRefreshedRowInsteadOfEarlierManagedConfiguration() {
        GenerationPlanEntity row = row();
        when(rows.lock(row.userId, row.sessionId, row.id)).thenReturn(Optional.of(row));
        doAnswer(call -> { row.revision = 7; return null; }).when(entities).refresh(row, LockModeType.PESSIMISTIC_WRITE);
        GenerationPlan locked = repository.lock(row.userId, row.sessionId, UUID.fromString(row.id));
        assertEquals(7L, locked.revision());
        org.mockito.InOrder ordering = inOrder(entities, rows);
        ordering.verify(entities).flush();
        ordering.verify(rows).lock(row.userId, row.sessionId, row.id);
        ordering.verify(entities).refresh(row, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void frozenRowCannotBeOverwrittenByDraftEvenWithNextRevision() {
        GenerationPlanEntity row = row();
        row.state = "FROZEN";
        when(rows.findByUserIdAndSessionIdAndId(row.userId, row.sessionId, row.id)).thenReturn(Optional.of(row));
        assertThrows(IllegalStateException.class, () -> repository.save(plan(row, 2)));
        assertEquals("FROZEN", row.state);
        verifyNoInteractions(entities);
    }

    @Test
    void unchangedOrSkippedRevisionCannotOverwritePersistedConfiguration() {
        GenerationPlanEntity row = row();
        when(rows.findByUserIdAndSessionIdAndId(row.userId, row.sessionId, row.id)).thenReturn(Optional.of(row));
        assertThrows(IllegalStateException.class, () -> repository.save(plan(row, 1)));
        assertThrows(IllegalStateException.class, () -> repository.save(plan(row, 3)));
        assertEquals(1L, row.revision);
        verifyNoInteractions(entities);
    }

    private GenerationPlan plan(GenerationPlanEntity row, long revision) {
        return new GenerationPlan("generation_plan", 1, UUID.fromString(row.id), UUID.fromString(row.userId), UUID.fromString(row.sessionId),
            revision, PlanState.DRAFT, codec.read(row.configuration, PlanConfiguration.class), null, row.createdAt, row.updatedAt, null);
    }
    private GenerationPlanEntity row() {
        GenerationPlanEntity row = new GenerationPlanEntity();
        row.id = UUID.randomUUID().toString(); row.userId = UUID.randomUUID().toString(); row.sessionId = UUID.randomUUID().toString();
        row.revision = 1; row.state = "DRAFT"; row.createdAt = Instant.parse("2026-10-13T00:00:00Z"); row.updatedAt = row.createdAt;
        CreateImagePlanArguments input = new CreateImagePlanArguments(new ImageTarget("IMAGE", null),
            new ImageIntent("image_intent", "Draft", new ImageRequirements("image_requirements", null, null, 1, null, null, null)),
            new ImageParameters("image_parameters", "prompt", null, List.of(), null), 0L);
        row.configuration = codec.write(new GenerationPlanDomain(new ModelCatalog()).create(input).configuration());
        return row;
    }
}
