# Lux Java backend architecture v1

## Scope and stack

Implement the complete Java business boundary: user-owned resources, sessions and messages, serialized Agent runs, execution-event persistence, asynchronous generation tasks, private assets, and replayable frontend notifications. Python Agent and model-connector behavior is represented by versioned contracts and test consumers. No paid provider calls are part of acceptance.

Use Java 17, Spring Boot 4.1.1, Spring MVC, MySQL 8.4/InnoDB, Spring Data JPA/Hibernate, Flyway, Kafka, and private local SeaweedFS storage through AWS SDK v2. Keep one Maven module under `com.lux.backend`. Redis, login, organization tenancy, shared resources, and real Python implementations are outside this version. The 2026-09-24 Hibernate migration supersedes the original JdbcClient persistence choice; the 2026-09-25 S3 migration replaces direct filesystem storage for new assets.

## Business packages

| Package | Responsibility |
| --- | --- |
| `user` | Users, explicit request identity, local user bootstrap |
| `chat` | Sessions, messages, run input batches, lifecycle coordination, run events, session event replay |
| `task` | Idempotent generation requests, provider state, result-import coordination |
| `asset` | Private S3 storage, uploads, metadata, owner-checked range downloads and legacy file reads |
| `infrastructure` | Kafka adapters, durable inbox/outbox, publication and recovery workers |
| `common` | Error responses, JSON serialization, identifiers and validation shared by actual callers |

Controllers adapt HTTP requests to application services; services own business transactions; feature repositories encapsulate Hibernate entities and Spring Data queries. Transport listeners call the same application services. Entities use scalar foreign-key IDs and explicit scoped queries; there are no cascaded session/message collections. Immutable API records remain separate from persistence entities. Native SQL is limited to insert-if-absent receipts, local user bootstrap, and inbox coordination. There is no workflow framework or generic CRUD service hierarchy.

## Persistence and ownership

Core tables are `user`, `session`, `message`, `agent_run`, `run_event`, `task`, and `asset`. `run_input` associates an ordered batch of user messages with a run. `outbox_event` and `inbox_event` provide reliable delivery. `session_event` provides frontend replay and is not a chat message. `http_request` saves idempotent HTTP mutation responses, including queue-continue, so a delayed retry cannot resume a later abnormal stop.

Every private row has a non-null `user_id`. Parent references preserve both owner and session identity where applicable. Resource IDs are UUIDs, ordering uses integer sequences, timestamps use UTC, and extensible content uses JSON. Queryable relationships and lifecycle fields remain ordinary indexed columns.

Flyway remains the schema authority. V1 is unchanged; V2 adds non-null versions to mutable tables that lacked them. Hibernate validates the schema and Open Session in View is disabled. UUID/hash mappings preserve existing CHAR columns, JSON remains native JSON, and Instant fields use UTC DATETIME(6) with microsecond precision. Append-only event/input records and immutable assets do not carry artificial versions.

Hibernate `@Version` detects conflicting updates to mutable rows. Flush changes before constructing DTOs or events that expose a new version, and explicitly flush parent inserts before scalar foreign-key children. Native/bulk coordination queries compare and increment versions themselves and refresh the affected managed entity afterwards. Task versions may advance for internal recovery/lease updates as well as visible changes; consumers compare newer versions without requiring consecutive values.

The diagnostic inbox retains the claimed user identity even for malformed/unknown users; it intentionally has no user foreign key. Application services verify actual resource ownership before changing business state. Invalid partition keys are durably rejected before inbox application. Conflicting event-ID reuse retains the original envelope and diagnostic conflict data.

`/api/v1/users/{user_id}/...` and `/internal/v1/users/{user_id}/...` explicitly select an existing local user. Controllers use `@PathVariable` and shared user lookup/UUID validation; the former CurrentUser resolver and header convention have been removed. The path is not authentication and cannot prevent impersonation. Invalid or unknown users are rejected. All read, write, stream, batch, tool and asset operations enforce ownership. Child ownership comes from validated parents, never from model-selected arguments. Async work carries explicit ownership rather than relying on a request thread-local.

## Message and run transactions

Before changing message order, queued inputs, active-run ownership, or lifecycle state, read the owning session and immediately update its version with a compare-and-set condition. A zero-row result aborts the transaction with an optimistic conflict; a successful update refreshes the session before child operations. This establishes a database write boundary before inspecting the queue, rather than relying only on a late commit-time version check. Saving a new input, claiming the entire currently queued batch, creating a run and its assistant placeholder, and recording `RUN_START` in the outbox occur in one transaction. Inputs arriving after that boundary await another run.

Only an assistant message has a direct `run_id`; user inputs are linked through `run_input`. A run owns one assistant placeholder, which can contain text and multiple task cards. Claimed inputs cannot be edited or withdrawn.

Successful run completion requires durable final output and all preceding run events. It clears the active slot and claims the next batch. Failure or confirmed cancellation preserves queued messages and pauses dispatch. Uncertain status triggers reconciliation and never releases the slot. Cancellation is requested first and finalized only by confirmation from the execution owner.

No network or file transfer occurs inside a database transaction. Text and task-card updates refresh and merge the existing assistant message after session coordination; Hibernate checks the message version when flushing. The same refresh rule applies to previously loaded run/task state used for mutation.

Use READ_COMMITTED for business mutations. Version-checked updates still acquire normal InnoDB write locks until commit; they do not make transactions lock-free. Message snapshot reads use REPEATABLE_READ so their event watermark matches the snapshot. Operations needing owner coordination perform its version compare-and-set before session coordination. Bootstrap, serializers and database connections use UTC. The single application instance is also the single writer to its dedicated asset bucket and staging directory.

`DatabaseTransactions` retries optimistic/write-lock conflicts at the outermost transaction boundary, including commit failures, up to eight attempts with bounded backoff. Joined transactions never retry locally after rollback-only marking. Retried callbacks contain database work only; file transfers and Kafka sends are outside them. HTTP idempotency receipts and callback effects commit together, and exhausted conflicts return HTTP 409 `CONCURRENT_MODIFICATION`. Existing edit APIs have no expected-version parameter, so this protects overlapping database transactions without introducing a browser stale-edit rejection contract.

## Tasks and assets

Agent task creation is an idempotent HTTP operation tied to a validated run/tool call. It creates the task, adds its card to the original assistant message, and writes a generation command atomically. The HTTP response returns the task identity without waiting for generation.

Task lifecycle is `CREATED -> SUBMITTED -> RUNNING -> IMPORTING -> SUCCEEDED`, with confirmed provider errors producing `FAILED`. Intermediate provider stages may be omitted. Provider success starts private file import; success is exposed only after the complete file and asset metadata are durable. File import failures retry transfer, never generation.

`AssetService` owns business metadata and idempotency; `AssetStore` owns bytes. Its default `S3AssetStore` uses a dedicated private bucket with owner/asset UUID keys. Stage uploads privately, enforce transfer limits and declared content length, compute checksums, then PUT a replayable file body using AWS SDK v2. A persisted asset's replacement must match its committed size and checksum before publication. Recovery resumes incomplete imports and removes abandoned staging files and orphan objects conservatively; existing metadata and owners with unfinished imports retain their objects. Transfers and checksum work stay outside database transactions.

Asset endpoints validate ownership before streaming bounded content, forwarding ranges to S3. Permanent public storage URLs are not frontend asset identities. A confirmed missing S3 object permits read-only fallback to the legacy local directory; unavailable or denied storage does not. New writes and cleanup do not modify legacy files, and no automatic data or schema migration is performed. See the [local S3 design](2026-09-25-local-s3-storage-design.md) for configuration and compatibility boundaries.

Task updates refresh the original card and emit `TASK_UPDATED` to Python. They do not append a chat bubble, reopen the originating run, start a new run, or resume a paused queue. Python updates the original tool message and manages model context itself.

## Delivery contracts

| Topic | Producer and consumer | Messages |
| --- | --- | --- |
| `lux.agent.commands.v1` | Java -> Agent | Run start, cancel, status request, task update |
| `lux.agent.events.v1` | Agent -> Java | Run lifecycle, output, tool/progress observations, status response |
| `lux.generation.commands.v1` | Java -> Connector | Generation submit, status request |
| `lux.generation.events.v1` | Connector -> Java | Submission, progress, success, failure, status response |

Envelopes contain `schema_version`, `event_id`, `event_type`, `user_id`, related resource identities, event time and payload. Agent partitions use user/session identity; generation partitions use user/task identity. Runs have producer sequence numbers; tasks have authoritative Java versions.

Persist business state and the outbound envelope in the same MySQL transaction. Publish after commit, acknowledge delivery after Kafka confirmation, and tolerate replay. Persist incoming identities before acknowledging consumption. Reject ownership conflicts and unknown messages into diagnosable inbox state. A synchronized database/Kafka transaction is not assumed to be a distributed atomic commit.

Inbox receipt is its own durable transaction. Processing commits business effects and APPLIED together; transient failures and sequence gaps retain RECEIVED for retry. Kafka acknowledgment after durable receipt is safe even when application is deferred. A database failure before receipt causes unbounded broker redelivery. Run reconciliation requests replay of canonical events after the last applied sequence; RUN_STATUS observations cannot replace missing output or a canonical terminal event.

`RUN_START` contains only the newly claimed input batch and the required task-update cutoff. Python must synchronize that cutoff before calling the model. Java neither rebuilds conversation history nor reads LangGraph checkpoints.

Frontend SSE reads durable `session_event` records using a cursor and continues polling for committed changes. User-scoped subscription and replay are mandatory. Disconnecting does not cancel an execution. Clients can use native EventSource and direct media URLs because both paths carry the user identity. Use the initial `after` cursor or the reconnect Last-Event-ID header for SSE replay.

## Verification and delivery

Acceptance uses real MySQL, Kafka and SeaweedFS Testcontainers, MockMvc, and fake Agent/Connector workers. Cover concurrent input claiming, successful batch continuation, failure/cancellation pause, cross-user isolation, replay and idempotency, late result updates, import-only retry, private range downloads, SSE reconnect, and database-committed events surviving Kafka publication failure. A separate storage-only suite checks actual AWS SDK CRUD, private access and multipart compatibility without MySQL or Kafka.

Run `./mvnw verify` for the complete suite. Maintain runnable local infrastructure configuration and document HTTP/Kafka contracts, environment settings, bootstrap identity, and recovery behavior in the repository. No changes to instruction files, Git branches, or commits are part of implementation.

Implementation references: [HTTP/Kafka contract](../../api-contract-v1.md), [startup and verification](../../../README.md), [Hibernate migration plan](../plans/2026-09-24-hibernate-migration.md), and Flyway V1/V2 migrations. Java contract doubles represent both Python consumers in the Kafka integration suite. Real LangGraph state application and provider calls remain subsequent work. On 2026-09-23, the user deferred container acceptance because Docker Desktop could not start new containers; container-independent checks and authored container tests must be reported separately. Hibernate metadata/query bootstrap is covered without a database, while real schema validation, stale-write rejection, concurrent inbox application, outbox ordering, and transactional rollback tests require the deferred containers.
