# Hibernate Persistence Migration Implementation Plan

> **For agentic workers:** Use the executing-plans and dispatching-parallel-agents skills for the independent persistence packages, followed by requesting-code-review.

**Goal:** Replace production JdbcClient access with Spring Data JPA and Hibernate while preserving user ownership, HTTP/Kafka contracts, ordered session scheduling, and reliable asynchronous delivery.

**Architecture:** Keep feature packages and immutable API records. Add scalar-reference JPA entities and Spring Data repositories behind the existing business repository boundaries. Use Hibernate `@Version` for mutable rows and explicit version compare-and-set at session/user coordination boundaries; normal reads do not acquire pessimistic locks.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Boot-managed Spring Data JPA/Hibernate, MySQL 8.4, Flyway, Kafka.

## Global Constraints

- No public route or JSON field changes; no real provider calls.
- No edits to AGENTS.md, Git initialization, branches, commits, or Docker restart.
- Existing V1 is immutable. V2 adds version columns only to mutable tables that do not have them.
- Flyway owns the schema; Hibernate validates it; Open Session in View is disabled.
- Use scalar foreign-key IDs and separate paginated queries, not cascaded conversation graphs.
- JSON columns remain JSON; timestamps remain UTC DATETIME(6); existing UUID/CHAR columns are retained.
- File and Kafka I/O stay outside database transactions. Retrying a transaction never repeats file downloads or provider requests.
- The user has deferred real MySQL/Kafka container acceptance. Compile those tests, run all independent tests, and explicitly record that limit.

## Transaction and Repository Contracts

- `ChatRepository.sessionForMutation(user, session)` reads the current version, executes an immediate version-guarded update, and refreshes the managed session. A zero-row update throws an optimistic conflict. This establishes the queue batch boundary before reading or mutating child rows; it still holds a normal database write lock until commit.
- `ChatRepository.session(user, session)` is a normal read. Child writes require the enclosing coordinated session transaction. Hibernate flushes before DTOs containing changed versions are emitted.
- `AssetRepository.coordinateOwner(user)` uses the same explicit version check on the user row for the existing upload/import idempotency boundary. When both are needed, user coordination precedes session coordination.
- `TaskRepository.getForMutation(user, task)` refreshes a previously loaded entity after session coordination; it does not acquire a pessimistic lock.
- `DatabaseTransactions` extends `TransactionTemplate`. It retries retryable optimistic/write-lock conflicts in a fresh transaction with bounded backoff. An existing transaction is joined without local retries; only the outermost boundary may retry.
- HTTP idempotency starts the transaction before invoking its callback. Direct send/task-create entry points also use DatabaseTransactions. Inbox retries retain the durable received event.
- Native SQL is restricted to atomic insert-if-absent receipts and explicit coordination CAS where needed. ORM bulk operations must explicitly account for versions and stale managed entities.

## Task 1: Foundation and Failure Contract

Files: `pom.xml`, `application.yml`, `common/DatabaseTransactions.java`, `common/ApiErrors.java`, `V2__hibernate_versions.sql`, `OptimisticConflictApiTests.java`, `DatabaseTransactionsTests.java`.

- [x] Add a failing HTTP test proving an optimistic conflict resolves to 409 rather than an unhandled exception.
- [x] Add fresh-transaction retry tests, including commit-time failure, bounded exhaustion, and no retry for validation failures.
- [x] Replace the JDBC starter with the JPA starter; set `spring.jpa.open-in-view=false`, `spring.jpa.hibernate.ddl-auto=validate`, UTC and DATETIME-compatible temporal mapping.
- [x] Add V2 versions for user, agent_run, http_request, inbox_event, and outbox_event.
- [x] Implement and run targeted tests, without starting containers.

## Task 2: Independent Persistence Packages

Files: feature-local `*Entity.java`, `*JpaRepository.java`, existing user/asset/task/infrastructure repositories and their relevant tests.

- [x] User/assets: map immutable asset metadata; replace SQL in bootstrap and maintenance with repository calls; preserve upload ownership and duplicate-upload behavior.
- [x] Tasks: map versioned task state, retain import leasing and monotonic provider events; flush version changes before TASK_UPDATED construction; make stale-task recovery one transaction per session/task.
- [x] Inbox/outbox/HTTP receipts: map durable identities and state; preserve per-key ordering and atomic callback+receipt commit; retain stable native insert-if-absent where necessary.
- [x] Do not add versions to append-only records or immutable assets solely for uniformity.

## Task 3: Chat and Optimistic Coordination

Files: chat entities/repositories, `ChatRepository.java`, `SessionService.java`, `RunCoordinator.java`, `RunEventService.java`, `RunRecovery.java`, and their tests.

- [x] Map sessions, messages, runs, run inputs, run events, and session events.
- [x] Replace boolean lock arguments with named read/mutation methods.
- [x] Implement immediate session version CAS, managed entity writes, explicit insert ordering for scalar foreign keys, and fresh reads after coordination.
- [x] Keep all queued inputs in one next run; preserve failure/cancel pause and untouched queued messages.
- [x] Preserve read-only message snapshot transaction and durable SSE cursor behavior.

## Task 4: Acceptance and Documentation

Files: persistence mapping tests, existing Testcontainers integration tests, README, API contract, and architecture/message specs.

- [x] Build Hibernate metadata without a database to catch mapping/bootstrap failures.
- [x] Add real MySQL regression cases for stale-version rejection and rollback of child rows/outbox after a concurrency conflict; retain existing end-to-end tests.
- [x] Adapt HTTP wiring tests to JPA boundaries without claiming they validate database behavior.
- [x] Run `./mvnw -B -ntp '-Dtest=*Tests,!*IntegrationTests,!LuxBackendApplicationTests' verify`.
- [x] Review the implementation independently; fix substantive findings and rerun affected tests.
- [x] Document remaining native statements, retry behavior, version semantics, and deferred `./mvnw verify` container acceptance.

## Execution Results

- The container-independent `verify` command passed 69 tests and produced the executable JAR. This includes actual Hibernate metadata and Spring Data query bootstrap with the database boundary disabled, plus queue/lifecycle, ownership, retry, delivery, storage, and HTTP checks.
- New regression coverage targets stale entity versions, rollback of run/outbox effects after a late version conflict, duplicate concurrent inbox processing, and per-topic/key outbox ordering. All integration test sources compile; execution remains deferred with the existing real MySQL/Kafka acceptance suite.
- Independent review identified a transaction-only repository lookup in a new integration test; the assertion now uses an ordinary scoped read. No other actionable findings remained after correction.
- Production code no longer uses JdbcClient or SELECT FOR UPDATE. Native statements remain intentionally confined to bootstrap, atomic receipt insertion, and inbox version coordination.
- V1 is unchanged. V2 has not been applied to a running database during this migration. Schema validation and actual MySQL JSON/temporal/concurrency behavior still require `./mvnw verify` once the user resumes container acceptance.
