# Lux Java Backend

Java business service for Lux: user-owned Sessions, independent inputs, Python-owned Run/Turn/Item projections, reusable generation plans, asynchronous generation Tasks, private Assets, Kafka delivery and SSE replay.

Java 17, Spring Boot 4.1.1, one Maven module, Spring MVC, Spring Data JPA/Hibernate, MySQL 8.4, Flyway, Kafka and local SeaweedFS object storage through AWS SDK for Java v2. The sibling Python agent executes a checkpointed LangGraph driver; this repository projects its facts and does not call a model. Provider execution remains an adapter boundary. Redis and login are not implemented.

## Local startup

Install JDK 17 and Docker with Compose. The Maven wrapper downloads Maven and dependencies on its first run.

1. Copy `.env.example` to `.env` and replace the database passwords and S3 secret with local values. Do not commit `.env`.
2. Start infrastructure and load the same configuration for Java:

```bash
docker compose up -d
set -a
source .env
set +a
./mvnw spring-boot:run
```

Compose provides persistent MySQL, Kafka and SeaweedFS volumes, bound to loopback ports 3306, 9092 and 8333. The Java application binds to `127.0.0.1:8080`. SeaweedFS 4.47 runs in single-node `mini` mode, initializes the private `lux-assets` bucket, and requires the configured S3 credentials. Start only storage with `docker compose up -d seaweedfs` when the other infrastructure is already managed separately. The Kafka native image is intended for local development and tests, as described in the [Apache Kafka Docker documentation](https://kafka.apache.org/40/getting-started/docker/). Run Java and the eventual Python services on the host with this Compose configuration; the advertised broker address is `localhost:9092`.

Flyway activates the fresh v2 baseline under `src/main/resources/db/session-storage`. It creates the complete twelve-table business schema; Hibernate validates it. The historical `db/migration/V1` and `V2` files remain unchanged, and are not the active migration location. The October 13 local acceptance corrects the fresh baseline's nullable CHECK predicates to explicit Boolean comparisons for MySQL 8.4. This changes the active V1 checksum; already-applied schemas need an explicit migration decision, never automatic Flyway repair or reset. Existing v1 schemas fail validation or table creation; startup never drops or translates them. Select a fresh database through `LUX_DB_URL` and initialize its user/grants separately before starting this version. Existing development data was declared disposable, but this implementation does not execute a reset. The local bootstrap creates user `00000000-0000-0000-0000-000000000001` if missing.

Stop old Java/agent consumers before activating v2. New v2 topics declare a 32 MiB record capacity; both clients use that request/partition-fetch capacity and a 64 MiB total fetch budget. Complete UTF-8 envelopes have a 31 MiB budget to leave protocol overhead. Existing topics must be explicitly configured to the same broker limits before activation; topic creation alone does not update an existing topic. Use fresh Python Store/checkpoint paths and the v2 topics/groups; do not replay old Run commands as new Session inputs. Java publishes generation v2 commands with independently decoded legacy or normalized plan requests and also accepts the documented legacy callback format. Coordinated connector consumer activation remains required; legacy callback decoding alone does not provide outgoing-command compatibility. The normalized BytePlus routes are registered but disabled by default; enabling them requires the matching Connector configuration described below.
```bash
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/api/v1/users/00000000-0000-0000-0000-000000000001
```

Only Actuator health endpoints are exposed. Every business and internal HTTP route identifies an existing user through `/users/{user_id}`. The path is a trusted local user selector, not authentication: anyone who knows another user's ID can select that user's scope. Controllers validate the path user explicitly; `X-User-Id` is no longer used. Do not expose this service or its broker publicly.

Stop containers with `docker compose stop`. Preserve the named volumes and any legacy asset directory together when backing up local data. Do not delete the SeaweedFS volume or legacy files during the storage transition.

For the frontend, all three business services and middleware as containers, use
the separate [unified local Compose entrypoint](deploy/local/README.md). It builds
the Java, Agent, Connector and Frontend images from their existing sibling
repositories and uses service DNS, persistent volumes and runtime memory limits.
The root Compose above remains the middleware-only stack. Real model/provider execution defaults
disabled; the integration override supplies local HTTP fixtures for complete
container acceptance without API keys.
The [container acceptance report](docs/superpowers/plans/2026-10-13-compose-container-integration-report.md)
records the executed startup, generation, restart and cleanup evidence.

## Configuration

| Environment variable | Default / purpose |
| --- | --- |
| `LUX_DB_URL` | `jdbc:mysql://localhost:3306/lux?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| `LUX_DB_USER` | `lux` |
| `LUX_DB_PASSWORD` | Required local database password; also used by Compose |
| `LUX_DB_ROOT_PASSWORD` | Compose database initialization only |
| `LUX_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `LUX_KAFKA_MAX_POLL_INTERVAL_MS` | `900000`; must exceed the single-record import budget by at least 60 seconds |
| `LUX_TASK_IMPORT_TIMEOUT_MS` | `240000`; shared monotonic callback budget, checked across sources and object verification |
| `LUX_AGENT_BASE_URL` | Empty disables proactive source observation; set the Python internal HTTP base URL to repair missing facts |
| `LUX_STORAGE_PROVIDER` | `s3`; use `local` only for legacy-only operation or local tests |
| `LUX_STORAGE_ROOT` | `./data/assets`, legacy read directory in S3 mode; writable storage in local mode |
| `LUX_STORAGE_STAGING_ROOT` | `./data/staging`, private temporary upload files in S3 mode |
| `LUX_S3_ENDPOINT` | `http://127.0.0.1:8333`, S3 service endpoint |
| `LUX_S3_REGION` | `us-east-1`, signing region |
| `LUX_S3_BUCKET` | `lux-assets`, dedicated private bucket, initialized by local Compose |
| `LUX_S3_ACCESS_KEY` | Required for S3 mode and Compose; local service credential |
| `LUX_S3_SECRET_KEY` | Required for S3 mode and Compose; keep in ignored `.env` |
| `LUX_MAX_ASSET_BYTES` | `104857600`, per uploaded/downloaded asset |
| `LUX_MAX_UPLOAD_SIZE` | `100MB`, servlet multipart file limit |
| `LUX_MAX_REQUEST_SIZE` | `101MB`, multipart request limit |
| `LUX_BOOTSTRAP_USER` | `true` |
| `LUX_DEFAULT_USER_ID` | `00000000-0000-0000-0000-000000000001` |
| `LUX_WORKERS_ENABLED` | `true`; disables scheduled publication, non-generation Inbox recovery, source observation and maintenance when false; Kafka listeners still receive events |
| `LUX_BIND_ADDRESS` | `127.0.0.1` |
| `SERVER_PORT` | `8080` |

Changing initialization passwords after a MySQL volume has been created does not change existing database users. Configure the application with the actual credentials for that volume.

## First session

Create a session using a fresh UUID idempotency key, then copy its returned `id`:

```bash
curl -X POST http://127.0.0.1:8080/api/v1/users/00000000-0000-0000-0000-000000000001/sessions \
  -H "Idempotency-Key: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
  -H 'Content-Type: application/json' -d '{"title":"First session"}'
```

Send a message to `/api/v1/users/{user_id}/sessions/SESSION_ID/messages`:

```json
{
  "client_request_id": "a-new-canonical-uuid",
  "content": [{"type": "text", "text": "Create a landscape image"}]
}
```

The response is HTTP 202 with `item`, containing its stable ID, Session registration position, independent input sequence, source/kind and content. Java creates no Run or Turn. The same `client_request_id` replays the stored HTTP response; its scope and request hash live only in `http_request`.

Load `/api/v1/users/{user_id}/sessions/{id}/items`. Its page contains Items plus referenced Run/Turn projections and `last_event_sequence_no` from the same read-only REPEATABLE_READ snapshot. Follow SSE strictly after that cursor. Items use immutable Java registration order. USER/SYSTEM inputs split one Run's visible output into stretches: M1, A1(R1), M2, A2(R1) stays in that order. Updating a streaming Item keeps its original position. Python's Run/Turn/Item indexes express source execution order independently.

Streaming SSE sends each consecutive `ITEM_TEXT_DELTA` immediately, with a stable Item identity and `stream_seq`. Append one temporary text block for each fragment, preserving whitespace. `ITEM_CONTENT_REPLACED` replaces the entire payload/status/source revision when the model's full response is saved. Ignore duplicate/old deltas and any delta after terminal replacement. Response completion does not finish a Turn or Run. `final_item_id` from the completed Run selects the final answer.

`POST .../sessions/{id}/stop` and `/continue` use `Idempotency-Key` and return a control event ID. They do not create Items or choose a Run. Python finishes the started Turn after stop, preserves pending inputs, ends that Run, and starts a fresh Run for a later wake.

Task completion creates an independent SYSTEM/TASK_EVENT input carrying only `task_id`; the model queries `get_task` to obtain a new immutable tool observation. The frontend queries owner-scoped Tasks and private Assets. Media URLs remain `/api/v1/users/{user_id}/assets/{id}/content`, with Range support.

Subscribe to `/api/v1/users/{user_id}/sessions/{id}/events` using EventSource or streaming fetch. Supply `?after=...` initially; native EventSource sends `Last-Event-ID` on reconnect. Resource invalidations and typed Item updates are persisted for replay. A later page refresh must not jump an already active SSE cursor. Disconnecting does not stop execution.

See the [v2 HTTP and Kafka contract](docs/api-contract-v2.md). The [v1 contract](docs/api-contract-v1.md) is historical and is not the active execution interface.

## Persistence

Feature repositories encapsulate Spring Data queries and Hibernate entities. Services work with immutable records; HTTP responses never serialize managed entities. Relationships use scalar IDs and separate scoped queries, avoiding automatic loading of entire message/event histories. Native SQL remains for atomic insert-if-absent receipts, local user bootstrap, and inbox coordination.

Flyway owns schema changes; Hibernate runs with `ddl-auto=validate` and `open-in-view=false`. UTC timestamps use MySQL `DATETIME(6)`; application-generated persisted timestamps are truncated to microseconds. `hibernate.type.preferred_instant_jdbc_type=TIMESTAMP` keeps Hibernate's Instant mapping compatible with this schema.

Mutable entities use `@Version` for local database concurrency. Python `source_revision` independently prevents older complete snapshots from replacing newer source state; equal revisions must have identical source fields. Out-of-order facts can create genuine identity shells with unknown content/status, and do not wait for a global event gap before display.

Input, Item registration and SSE allocation serialize on the Session row. Input/SSE allocation uses a locking current read of the latest retained position; page reads use a consistent non-locking snapshot. No Session input-progress or active-Run field participates in scheduling. Database conflicts retry the whole outer transaction, including commit, up to eight attempts with bounded backoff. File transfer and Kafka calls stay outside retry callbacks. Exhausted conflicts return HTTP 409 with code `CONCURRENT_MODIFICATION`.

Versions may skip values. Existing Session edit APIs do not accept an expected browser version; generation plan/focus mutations require their explicit expected revisions. Private generated objects are immutable; newly verified partial Asset metadata advances the Task version and frontend invalidation, while duplicate metadata does not.

## Generation plans

The agent uses `get_model_catalog`, `read_generation_plan`, `create_generation_plan`, `update_generation_plan`, `change_generation_target`, `clone_generation_plan`, `focus_generation_plan` and `submit_generation_plan` through POST `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-tools/{tool_name}`. The named invocation includes the original call Item UUID as `operation_id`; a typed parent receipt preserves the original observation or proven business rejection. Recover uncertain calls with the same identity or GET `generation-plan-tool-operations/{operation_id}` under the same Session path.

GET `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-context` returns authoritative focus, nullable focused plan snapshot and catalog. Frontend reads use `/api/v1/users/{user_id}/sessions/{session_id}/plans/{id}`, `/plans` and `/plan-focus`; plan pages use paired `beforeCreatedAt`/`beforeId` query parameters and a limit of 1–50. These reads use a consistent database snapshot; read validation consumes cached verified metadata and inspector availability.

First accepted submission freezes once and atomically creates the requested N independent single-file Tasks, N generation Outbox envelopes and one parent operation receipt. Replaying the same parent returns its original child identities; a new submission of a frozen revision creates another N Tasks with the unchanged request/count. Focus and plan revisions use compare-and-set checks. The catalog lists Seedream 4.5, Seedream 5.0 Lite/Pro and Seedance 2.5, disabled by default. `LUX_BYTEPLUS_GENERATION_ENABLED=true` enables their exact execution/result-verifier routes; configure the matching Connector and `ffprobe` before opting in. Initial profiles use finite size presets, one result per Task, and PNG/JPEG image references (3 MiB for 4.5/Lite, 4 MiB for Pro, 1.5 MiB for Seedance) to stay within inline transport limits. These registrations do not establish account activation or live provider acceptance. The legacy `/internal/v2/users/{user_id}/tasks` entrypoint accepts new legacy payloads only; normalized generation must enter through plan submission, while stored normalized Tasks remain decodable.

The initial submission policy allows at most 32 Tasks per explicit submission, independently of model capabilities. Result import checks one file against its frozen encoding, dimensions, duration tolerance and audio-track expectations. Reference inspection uses verified bytes and checksum-bound metadata. PNG/JPEG use the JDK decoder; video and audio inspection require `ffprobe`. Configure its executable with `lux.generation.ffprobe-command` (default `ffprobe`) and its byte limit with `lux.generation.inspection-max-bytes` (default 268435456). Probe execution is bounded by the shared inspection/import deadline. A missing executable or temporary storage failure remains a technical recovery case; confirmed invalid media fails verification.

## Asset storage

`AssetService` owns metadata, ownership, upload idempotency and database coordination. `AssetStore` owns byte transfer and cleanup; its default `S3AssetStore` implementation talks directly to SeaweedFS using the AWS S3 SDK. The SDK BOM pins its modules to 2.55.5 because Spring Boot does not manage their versions. The synchronous URL connection transport avoids adding a second HTTP framework.

Objects use the stable key `{user_id}/{asset_id}` in one dedicated private bucket. MySQL stores metadata and stable asset IDs, not public S3 URLs. New writes stage bytes privately on disk, enforce the configured limit, calculate SHA-256, then upload with a replayable request body. A replacement for a persisted result must match its recorded size and checksum before upload. Application uploads currently use a single PUT, bounded to 100 MiB by default; the integration test separately checks the endpoint's multipart compatibility.

Java checks ownership before opening a stream. Public and internal content routes remain unchanged; full, single-range and multipart-range responses stream through Java. Single ranges issue S3 Range GETs, so seeking in a video does not download and discard its preceding bytes. Credentials and raw bucket access are never sent to the browser.

In S3 mode, a missing object permits read access to its existing file under `LUX_STORAGE_ROOT`. Connection failures and access denial remain errors. New writes, orphan cleanup and unused-upload deletion affect S3 only; no existing local file is automatically migrated or deleted. Keep the old directory until an explicit migration is completed. Switching to `local` cannot read assets already written only to S3. No database migration is needed for this change.

## Verification

The [cross-project integration readiness and test report](docs/superpowers/plans/2026-10-13-cross-project-integration.md) records current ownership, activation gates, executed local evidence and the broader 32-case acceptance matrix. The [implemented provider design](docs/superpowers/specs/2026-10-13-provider-integration-design.md) specifies Agent-direct ChatOpenAI with authorized image/frame observations, Connector-owned BytePlus generation, audited original-evidence attachment and Java reference-ratio admission. The [local process runner](scripts/integration/README.md) uses real Java/Agent/Connector, MySQL/Kafka/S3 and loopback model/provider HTTP fixtures; live account and output-quality acceptance waits for API keys.

Full acceptance requires a working Docker daemon and starts isolated MySQL/Kafka/SeaweedFS Testcontainers; it does not use or reset Compose volumes:

```bash
./mvnw verify
```

`WorkflowIntegrationTests` covers concurrent independent input allocation and original input repair, HTTP control idempotency, late source snapshots/deltas, owner isolation, SSE replay and Java Kafka/HTTP contract doubles for generation. The receiving worker imports before acknowledging Kafka, then emits one Session Task notification. The doubles do not prove real Python/provider interoperability.

`HibernateConcurrencyIntegrationTests` covers stale projection updates and atomic input/Outbox/SSE rollback. `DeliveryIntegrationTests` covers concurrent Inbox application and per-topic/key Outbox ordering. `ChatSchemaIntegrationTests` directly checks identity shells, ownership/FKs, exact call IDs and source shape constraints. These require isolated MySQL; workflow tests also require Kafka and S3.

Storage-only acceptance can run independently of MySQL and Kafka:

```bash
# Starts its own isolated SeaweedFS container.
./mvnw -Dtest=S3IntegrationTests test

# Or use the local Compose S3 endpoint with credentials loaded from .env.
set -a
source .env
set +a
LUX_TEST_S3_ENDPOINT=http://127.0.0.1:8333 ./mvnw -Dtest=S3IntegrationTests test
```

This test creates a unique test bucket and deletes only its own objects, uploads and bucket. It checks signed PUT/HEAD/GET/Range/LIST/DELETE, content integrity, anonymous-read denial, multipart completion and abort. It does not restart an external endpoint or validate crash recovery.

Container-independent checks and packaging:

```bash
./mvnw '-Dtest=*Tests,!*IntegrationTests,!LuxBackendApplicationTests' verify
```

These tests cover source revisions, response replacement, fragments, Session inputs, protocol fixtures, ownership, typed HTTP wiring, import/ACK classification, immutable storage and transaction conflicts. Wiring checks bootstrap Hibernate metadata and Spring Data queries without a real MySQL schema or broker. Real constraint, transaction and transport acceptance still requires the full Docker command. If Docker cannot start isolated containers, report those checks as unverified; unit tests do not substitute for them.

`GenerationPlanIntegrationTests` uses isolated MySQL and synthetic registrations to check the forward plan migration, owner/Session foreign keys, focus compare-and-set, exact parent receipt replay, batch acceptance and rollback after a child failure. It performs no provider generation. If the JVM cannot attach Mockito's inline agent, pass its existing managed JAR explicitly through Maven's `-DargLine=-javaagent:/absolute/path/to/mockito-core-<managed-version>.jar`; no additional test dependency is needed.

## Recovery and operating boundaries

- Java input/HTTP receipt/Outbox/SSE commit atomically. Delivered input envelopes remain retained for repair throughout the input history's lifetime. Broker sends retry the same stable identity and content, preserving topic/key order.
- Agent event receipt precedes ACK; its business application and APPLIED marker commit together. Technical failures remain RECEIVED for non-generation Inbox recovery. Invalid/conflicting records retain diagnostic evidence.
- Generation callback handling stays on the Kafka receiving worker. It saves the manifest/IMPORTING in a short transaction, transfers and verifies bytes outside a transaction, then commits Task terminal state, one SYSTEM input, Outbox, SSE and Inbox APPLIED together before returning. Generic Inbox recovery excludes generation topics; there is no import polling worker or lease. Pre-result Task status queries use a separate fair read-only cursor and never resume imports.
- Confirmed source GET/read/content failures end the Task with IMPORT failure. Ambiguous object publication, database or ownership interruption throws for unacknowledged recovery using the same IDs. Failed Tasks are not retried. Partial verified Assets remain accessible.
- Kafka auto-commit is disabled, ACK mode is RECORD and max-poll-records is one. The shared import budget limits source/object work, with bounded socket waits; it cannot interrupt a blocked JDBC call. Keep database connection/lock waits bounded and retain the 900-second poll margin. Ownership loss never justifies advancing an unfinished offset.
- Source observation reads Python's event high watermark and canonical snapshots, independently projects them, and requests original missing ranges. It never treats a missing heartbeat/event as proof that a Run ended. Bounded source pages use a separate advisory cursor under a fixed source head, while missing low ranges are repaired independently. Gaps affect replay/affected fragment assembly, not unrelated source display.
- Python's single local Session driver and native checkpoint own execution/context. Its Store and checkpoint are separate transactional boundaries with stable receipt/intents and reconciliation. Java stores no model context and never rebuilds or schedules it.
- Generated objects use deterministic Task/source identities and conditional publication. Duplicate receiving workers verify original bytes/metadata rather than overwrite. Maintenance retains objects for unfinished imports and removes only bounded inactive staging/orphan candidates; legacy files are not reset.

Storage design: [English](docs/superpowers/specs/2026-10-09-session-run-turn-item-storage-design.md), [Chinese](docs/superpowers/specs/2026-10-09-session-run-turn-item-storage-design.zh-CN.md). Historical architecture: [Java design](docs/superpowers/specs/2026-09-23-java-backend-architecture-design.md), [session/message design](docs/superpowers/specs/2026-09-23-session-message-flow-design.md) and [local S3 design](docs/superpowers/specs/2026-09-25-local-s3-storage-design.md).

Implemented generation contracts for reusable plans, typed parameters, model capabilities and tools: [English](docs/superpowers/specs/2026-10-12-generation-plan-contract-design.md), [Chinese](docs/superpowers/specs/2026-10-12-generation-plan-contract-design.zh-CN.md), with a [complete JSON Schema](docs/fixtures/generation-plan-v1/contract.schema.json) and [structural examples](docs/fixtures/generation-plan-v1/examples.json). The eight plan tools, owner/Session-scoped persistence, original operation receipts and normalized single-file Task batching are implemented. Four exact BytePlus registrations are available behind an opt-in gate. Seedance's optional reference-ratio capability preserves omitted-field old JSON and requires coordinated Java/Agent readers before advertising the new field; real provider acceptance remains a separate gate.
