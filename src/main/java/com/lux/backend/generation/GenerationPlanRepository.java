package com.lux.backend.generation;

import com.lux.backend.common.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import static com.lux.backend.generation.GenerationPlanContract.*;

/** 持久化和恢复完整具名配置；Session 锁后才取得计划锁并执行写入。 */
@Repository
@Transactional(readOnly = true)
public class GenerationPlanRepository {
    private final GenerationPlanJpaRepository rows;
    private final EntityManager entities;
    private final GenerationPlanCodec codec;
    public GenerationPlanRepository(GenerationPlanJpaRepository rows, EntityManager entities, GenerationPlanCodec codec) {
        this.rows = rows; this.entities = entities; this.codec = codec;
    }
    public GenerationPlan get(String owner, String session, UUID id) {
        return snapshot(rows.findByUserIdAndSessionIdAndId(owner, session, id.toString()).orElseThrow(ApiException::notFound));
    }
    /** 写事务内 refresh 锁定当前行，避免较早读取的持久化上下文覆盖新版本。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public GenerationPlan lock(String owner, String session, UUID id) {
        entities.flush();
        GenerationPlanEntity row = rows.lock(owner, session, id.toString()).orElseThrow(ApiException::notFound);
        entities.refresh(row, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return snapshot(row);
    }
    public List<GenerationPlan> page(String owner, String session, PlansCursor cursor, int limit) {
        return rows.page(owner, session, cursor == null ? null : cursor.beforeCreatedAt(),
            cursor == null ? "" : cursor.beforeId().toString(), PageRequest.of(0, limit)).stream().map(this::snapshot).toList();
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(GenerationPlan plan) {
        GenerationPlanEntity row = new GenerationPlanEntity();
        row.id = plan.id().toString(); row.userId = plan.userId().toString(); row.sessionId = plan.sessionId().toString();
        row.createdAt = plan.createdAt(); copy(row, plan); entities.persist(row); entities.flush();
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(GenerationPlan plan) {
        GenerationPlanEntity row = rows.findByUserIdAndSessionIdAndId(plan.userId().toString(), plan.sessionId().toString(), plan.id().toString())
            .orElseThrow(ApiException::notFound);
        if (row.state.equals("FROZEN")) throw new IllegalStateException("Frozen plan is immutable");
        if (plan.revision() != Math.addExact(row.revision, 1)) throw new IllegalStateException("Plan revision must advance once");
        copy(row, plan); entities.flush();
    }
    private void copy(GenerationPlanEntity row, GenerationPlan plan) {
        row.revision = plan.revision(); row.state = plan.state().name(); row.configuration = codec.write(plan.configuration());
        row.frozenRequest = plan.frozenRequest() == null ? null : codec.write(plan.frozenRequest());
        row.frozenAt = plan.frozenAt(); row.updatedAt = plan.updatedAt();
    }
    private GenerationPlan snapshot(GenerationPlanEntity row) {
        return new GenerationPlan("generation_plan", 1, UUID.fromString(row.id), UUID.fromString(row.userId), UUID.fromString(row.sessionId),
            row.revision, PlanState.valueOf(row.state), codec.read(row.configuration, PlanConfiguration.class),
            row.frozenRequest == null ? null : codec.readNormalized(row.frozenRequest), row.createdAt, row.updatedAt, row.frozenAt);
    }
}
