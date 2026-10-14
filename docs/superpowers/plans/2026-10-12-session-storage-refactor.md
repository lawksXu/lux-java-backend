# Session Storage Refactor Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for independently owned modules, then perform specification and code-quality reviews. Steps use checkbox syntax for tracking. User authorization prohibits branches, commits, instruction-file edits, and changes to model connector or Python utilities.

**Goal:** Implement the approved Session/Run/Turn/Item storage and execution design in Java backend and Python agent.

**Architecture:** Java atomically accepts immutable Session inputs and projects Python execution facts. A single checkpointed Python Session driver owns Run/Turn creation, model/tool execution, stopping, recovery, and output identities. Inbox/Outbox and canonical journals provide recoverable handoffs; native LangGraph checkpoints remain a separate persistence boundary.

**Tech Stack:** Existing Java 17/Spring Boot/MySQL/Kafka infrastructure; Python 3.14/Pydantic, pinned LangGraph and native AsyncSqliteSaver. No provider calls or deployed database changes.

## Shared contract and activation

The authoritative design is `docs/superpowers/specs/2026-10-09-session-run-turn-item-storage-design.md`.

- Agent topics: `lux.agent.commands.v2`, `lux.agent.events.v2`; schema_version is 2.
- Commands use flat named envelopes with event_type INPUT_ACCEPTED, STOP_SESSION, CONTINUE_SESSION, QUERY_SESSION_EXECUTION, or REPLAY_SESSION_EVENTS. InputAccepted has an `item` member with id, sequence_no, input_sequence_no, source, kind, content. Controls have no Run/Turn identity. Query/replay add their declared bounds.
- ExecutionFact contains schema_version, event_id, event_type, occurred_at, user_id, session_id, event_seq, and `body`. event_type selects RUN_SNAPSHOT, TURN_SNAPSHOT, AGENT_ITEM_SNAPSHOT, MODEL_RESPONSE_COMPLETED, or ITEM_TEXT_DELTA. The named body has no duplicate type discriminator.
- Source identity references recursively carry actual Run/Turn/Item IDs and indices. Named payloads follow design section 6. Transport hashes use original JSON values, not normalized DTO reserialization.
- Generation v2 commands/events use `lux.generation.commands.v2` / `lux.generation.events.v2`. Java additionally reads legacy `lux.generation.events.v1` without requiring a Run projection, preserving legacy provider-acknowledgment semantics at the adapter. New commands have no invented legacy Run/call correlation. Connector service files remain untouched; activating its new consumer is a separate service task.
- Session-only internal tools: POST `/internal/v2/users/{owner}/tasks` accepts operation_id, session_id, kind, request and returns TaskSubmissionReceipt. GET `/internal/v2/users/{owner}/sessions/{session}/tasks/{task}` returns TaskQuerySnapshot. Original-operation lookup reconciles uncertain acceptance without generating a new operation.
- Input repair: GET `/internal/v2/users/{owner}/sessions/{session}/inputs?after_input_sequence_no=0&through_input_sequence_no=N&limit=100` returns original retained InputAccepted envelopes.
- A separate fresh-schema baseline at `src/main/resources/db/session-storage/V1__session_storage.sql` implements the target twelve tables. Keep old migration files as historical source. Point Flyway at the new baseline, with validation enabled: an old schema fails validation/creation instead of being silently erased or translated into fabricated source identities. Do not run a reset against existing data.
- Preserve filesystem snapshots under `/private/tmp/lux-storage-refactor-20261012/`; these directories have no Git metadata.

## Task 1: Java conversation storage and protocol

**Ownership:** chat package, IncomingEvent/EventProtocol/CommandPayload, fresh-schema SQL, chat unit tests.

- [x] Write failing behavior tests for immediate Session input delivery without Java Run creation; replayed HTTP request returns the same input; terminal Run accepts independently late Items; equal-revision conflicts; contiguous temporary fragments; atomic full-response conflict rejection.
- [x] Implement Session, Run, Turn, and Item entities/repositories with exactly the approved fields and constraints. Remove Message/RunInput scheduling and queued edit/withdrawal APIs after checking callers.
- [x] Implement named content, source snapshots, Java projection/page, and SSE wrapper types. Decode known structures once at ingress; retain raw JSON only as opaque protocol evidence.
- [x] Provide `SessionService.acceptTaskTerminal(owner, session, taskId)` inside the caller's transaction, and frontend-only Task invalidation. Task terminal notification uses the same input allocator/Outbox/SSE as user input.
- [x] Implement Session row-lock/current-read allocation, source revision validation, genuine identity shells, fragment buffering/drain, terminal whole-payload replacement, and readonly consistent page/SSE snapshots.
- [x] Implement original-envelope input repair and Session-scoped query/replay API. Java never selects input execution ownership.
- [x] Add fresh-schema baseline without automatically dropping old data and add isolated MySQL constraint/concurrency tests.
- [ ] Execute those MySQL integration tests once Docker can start the isolated fixtures; current attempts stall before a database starts.

Public shared calls:

```java
// TaskService calls this only inside its terminal transaction.
InputAcceptanceResponse acceptTaskTerminal(String owner, String session, String taskId);
// InboxProcessor invokes this inside the fact/application transaction.
void applyEvent(IncomingEvent.ExecutionFact fact);
// Outbox owns stable command identities and retained original repair evidence.
void enqueueEnvelope(String topic, String key, CommandPayload.Envelope envelope);
```

## Task 2: Python durable Session driver

**Ownership:** Python agent source, tests, pyproject.toml, README.

- [x] Write failing tests for Python-owned stable Run/Turn IDs; input incorporation once; steer only at Turn boundary; stop plus retained backlog; post-stop input starts a fresh Run; UNKNOWN blocks advancement.
- [x] Install exact compatible LangGraph/native-saver dependencies in the existing project venv and validate Python 3.14 compatibility with native checkpoint round trips.
- [x] Implement strict named v2 protocol models matching Java fixtures; preserve original event metadata and hash evidence during Kafka/HTTP repair.
- [x] Implement the seven AgentStore tables, durable admission ordinals, canonical Session event/Item fragment allocation, generation-aware publication, and original execution receipts. Refuse an incompatible legacy local store rather than silently recovering it as a new execution.
- [x] Build native StateGraph persistence steps with sync durability and Session thread_id. Drivers serialize per Session; callback writes do not update active graph checkpoint state.
- [x] Implement model and tool execution interfaces, immediate durable streamed facts, observed-only candidate starts, complete response receipts/bundles, call-result context gate, failure/UNKNOWN recovery, stop/wake ordering, and fixed-upper-bound input repair.
- [x] Wire real local application lifecycle and durable delivery interfaces; maintain lightweight health semantics. Model/provider execution remains injectable and must not issue paid calls during validation.
- [x] Run pytest, Ruff lint/format, dependency validation, and native-checkpoint restart tests.

Executor boundaries:

```python
class ModelExecutor(Protocol):
    def stream(self, request: ModelRequest) -> AsyncIterator[ModelTextFragment | ModelResponseObserved]: ...

class ToolExecutor(Protocol):
    async def execute(self, request: ToolExecutionRequest) -> ToolResultContent: ...
```

## Task 3: Session-owned Java Tasks and import

**Ownership:** Java task/asset packages and their tests.

- [x] Demonstrate the old object overwrite and Task Run-dependency behavior with failing tests.
- [x] Implement simplified Task fields, immutable original-operation request comparison, immediate submission receipts, and consistent get_task snapshots with actual Asset source_index.
- [x] Normalize new generation outcomes and permitted legacy observations at the Java adapter; preserve finer connector diagnostics in Inbox only.
- [x] Replace import leases/scans/retries with `TaskResultService.receive(event, markApplied)`: short manifest admission, I/O outside transactions, and atomic terminal Task/input/SSE/Outbox/APPLIED.
- [x] Implement immutable generated-object publication, deterministic identities, exact verified bytes/metadata, and safe duplicate/rebalance behavior for local and S3 stores.
- [x] Classify confirmed GET/read/content failures as terminal IMPORT failure; propagate database/control/uncertain publication errors for unacknowledged Kafka recovery.
- [x] Test duplicate receipt resume, accepted-sequence resume, offset-after-terminal ordering, terminal notification uniqueness, partial Assets, and immutable object collisions.

## Task 4: Infrastructure, cross-language integration, and completion review

**Ownership:** root; infrastructure other than shared protocol files, application config, shared fixtures, API docs, top-level integration tests.

- [x] Implement stable-ID typed Outbox envelope storage and original input lookup; retain technical publication state independently.
- [x] Make generation listeners complete synchronous import before confirming offsets; generic Inbox drain excludes generation events. Disable auto-commit, bound batch processing, and preserve completed-prefix commits.
- [x] Replace Run-targeted recovery with Session observation/replay, remove background import scanning, and keep unrelated Asset maintenance.
- [x] Add shared v2 Unicode/null/whitespace/timestamp fixtures and validate encoding/decoding and original identity/hash preservation in both languages.
- [x] Update HTTP/Kafka documentation and operational cutover steps, including fresh schema/local stores, old-worker/topic isolation, and unchanged connector scope.
- [x] Run targeted tests, clean non-container Java verification, Python pytest, Ruff, and dependency checks; attempt full `./mvnw verify` with isolated fixtures.
- [ ] Complete real MySQL/Kafka/S3 integration acceptance after Docker startup recovers. No development schema/provider account is used.
- [x] Review specification coverage first, then concurrency/failure handling and code quality. Fix findings and repeat only affected checks.
- [x] Compare snapshots and connector hash manifest; confirm no model connector/utils/instruction-file edits. Report actual tests and any external-integration limits.

## Implementation outcome and verification

Implementation is complete in Java backend and Python agent. Model connector and Python utilities were not edited; no instruction file, branch or commit was created/changed. No existing database was reset or migrated. Filesystem snapshots are retained under `/private/tmp/lux-storage-refactor-20261012/`.

- Java: `./mvnw clean '-Dtest=*Tests,!*IntegrationTests,!LuxBackendApplicationTests' verify` passes 153 tests and packages the application. All integration-test source also compiles. The full `./mvnw verify` attempt stalls starting isolated Testcontainers Ryuk; a bounded repeat was stopped after 45 seconds. Actual MySQL constraints/concurrency, Kafka and S3 remain unverified. Only this turn's verified test processes/containers were stopped/removed; existing services and volumes were untouched.
- Python: `.venv/bin/python -m pytest -W error` passes 58 tests; whole-project Ruff lint/format and `pip check` pass. Tests use real native LangGraph/SQLite persistence and injected executors/transports, not paid providers or live broker/service calls.
- Shared contract: six original JSON fixture digests match in both languages. English/Chinese design code blocks, historical schema evidence, current table inventory and local documentation links were checked.
- Scope: all 15 snapshotted model-connector files, both AGENTS files and historical Java V1/V2 migration bytes remain unchanged. The new fresh Java baseline has twelve business tables; AgentStore has seven custom tables plus separately owned native checkpoints/writes.

Independent reviews corrected immutable Task/Asset/source-operation references, source journal routing/fragment indexes, source timestamp/TEXT capacity parity, fair source/Task observation, durable post-stop wake dispatch, UNKNOWN-to-confirmed-failure reconciliation and full-envelope Kafka budgets. Oversized Java requests roll back before acceptance; oversized observed model completions retain their original receipt/checkpoint intent and block materialization before tools.

Activation requires a fresh Java database, fresh AgentStore/checkpoint pair, stopped old workers and matching v2 broker/client capacity. The model connector's v2 command consumer is outside this round; legacy callback decoding is not a substitute for that activation. ModelExecutor remains an injectable adapter; no built-in provider adapter was added. See the [active contract](../../api-contract-v2.md) and both READMEs.
