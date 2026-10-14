# Lux HTTP and Kafka contract v1

> Historical v1 contract. Current Session execution uses [the v2 contract](api-contract-v2.md); removed Run/message routes below are not current APIs.

## Shared conventions

JSON property names use snake_case. Resource and idempotency IDs are canonical lowercase UUIDs. Timestamps are ISO-8601 UTC. All HTTP routes below select an existing user explicitly through `/users/{user_id}`. Malformed user IDs return 400; unknown users and other-owner resources return 404; invalid lifecycle operations or conflicting idempotency input return 409. Errors use ProblemDetail with a `code` extension.

Database concurrency conflicts that exhaust the bounded transaction retry return 409 with code `CONCURRENT_MODIFICATION`. Reload the current state before retrying with the same operation identity and input. The Hibernate migration adds no expected-version input to existing edit APIs: entity versions protect overlapping database writes, not edits based on an earlier browser snapshot.

Creation of user messages uses `client_request_id`; asset uploads use `Idempotency-Key`; internal task creation uses `operation_id`. The other HTTP mutations listed below require an `Idempotency-Key` UUID and store their response in `http_request`. Retrying the same operation/input returns its saved response, even after subsequent state transitions; reusing its identity with different input returns 409. In particular, retrying an old queue-continue request cannot resume a later failure. Identities are scoped to the user. Queued-message edits do not change the original submission identity/hash.

The 2026-09-24 route migration replaces the former header-based API. There are no unscoped resource aliases or `/users/me` alias. The legacy `X-User-Id` header is ignored; only the path selects the user. Controllers validate the user and pass its normalized ID explicitly to business services. Database ownership filters and Kafka envelope identities are unchanged.

## HTTP routes

| Method and path | Input / result |
| --- | --- |
| GET `/api/v1/users/{user_id}` | Selected user record |
| POST `/api/v1/users/{user_id}/sessions` | `{title}`, idempotency header; 201 session |
| GET `/api/v1/users/{user_id}/sessions?limit=50` | Latest owned sessions, max 100 |
| GET `/api/v1/users/{user_id}/sessions/{id}` | Session, active run, pause flag and sequence counters |
| PATCH `/api/v1/users/{user_id}/sessions/{id}` | `{title}`, idempotency header |
| POST `/api/v1/users/{user_id}/sessions/{id}/messages` | `{client_request_id,content:[...]}`; 202 `{message,dispatched_run_id}` |
| GET `/api/v1/users/{user_id}/sessions/{id}/messages?after=0&limit=50` | `{items,next_after,last_event_seq}`, ascending immutable message sequence; max 200 |
| GET `/api/v1/users/{user_id}/sessions/{id}/messages/{messageId}` | One owned message |
| PATCH `/api/v1/users/{user_id}/sessions/{id}/messages/{messageId}` | `{content:[...]}`, idempotency header; queued user messages only |
| DELETE `/api/v1/users/{user_id}/sessions/{id}/messages/{messageId}` | Idempotency header; marks queued input WITHDRAWN; 204 |
| POST `/api/v1/users/{user_id}/sessions/{id}/queue/continue` | Idempotency header; clears pause and attempts batch dispatch |
| GET `/api/v1/users/{user_id}/runs/{id}` | Run lifecycle and latest applied sequence |
| GET `/api/v1/users/{user_id}/runs/{id}/events?after=0&limit=100` | Ordered event page, max 500 |
| POST `/api/v1/users/{user_id}/runs/{id}/cancel` | Idempotency header; requests cancellation, keeps slot until terminal confirmation |
| GET `/api/v1/users/{user_id}/tasks/{id}` | Task view with `assets` |
| POST `/api/v1/users/{user_id}/tasks/batch` | `{ids:[...]}`, max 100; owned task views; this read needs no idempotency key |
| POST `/api/v1/users/{user_id}/assets` | Multipart `file`, idempotency header; asset metadata |
| GET `/api/v1/users/{user_id}/assets/{id}` | Asset metadata |
| GET `/api/v1/users/{user_id}/assets/{id}/content` | Private bytes; supports `Range` and returns 206 as appropriate |
| GET `/api/v1/users/{user_id}/sessions/{id}/events?after=0` | SSE; `Last-Event-ID` takes precedence over query cursor |
| POST `/internal/v1/users/{user_id}/tasks` | Generation tool request below; 201 task view |
| GET `/internal/v1/users/{user_id}/tasks/{id}` | Same owner-scoped task view |
| GET `/internal/v1/users/{user_id}/assets/{id}` | Same owner-scoped metadata |
| GET `/internal/v1/users/{user_id}/assets/{id}/content` | Same private content behavior |

Message content is an ordered list of blocks. User inputs allow nonempty text blocks `{type:"text",text:"..."}` and owned asset references `{type:"asset",asset_id:"..."}`. Assistant blocks include `{type:"text",id:"answer",text:"..."}` and `{type:"task",task_id:"..."}`. The AI message is created at run dispatch and remains the same object throughout output and task completion. Withdrawn user messages remain in history with `queue_status=WITHDRAWN` and are never claimed.

The message snapshot's `last_event_seq` permits an atomic snapshot/replay handoff. Subscribe from that watermark, or subscribe first and buffer events while loading pages. Changes to existing early messages require refetching those identities, not merely appending new messages after the last sequence.

Task tool input:

```json
{
  "operation_id": "stable-uuid-for-this-tool-operation",
  "run_id": "active-owned-run-uuid",
  "tool_call_id": "stable-agent-tool-call-id",
  "kind": "IMAGE",
  "request": {"prompt": "A landscape", "asset_ids": []}
}
```

`kind` is IMAGE or VIDEO. Input `asset_id`/`asset_ids` references must belong to the run owner. A task view contains `id`, `session_id`, `origin_run_id`, `tool_call_id`, `kind`, `status`, `version`, `error_message`, timestamps and `assets`. Each asset exposes its stable ID, owner/task identity, filename, media type, size and SHA-256. Resolve content through the private API; provider URLs and request hashes are not exposed in task views.

Asset bytes use a private S3-compatible bucket; bucket keys and credentials are not part of this API. Java validates the path owner before storage access and streams content with `Cache-Control: no-store`, attachment disposition, and byte-range support. GET supports single, suffix and multiple ranges; valid ranges return 206, unsatisfiable ranges return 416 with `Content-Range: bytes */{size}`. HEAD returns metadata headers without an object body. Missing content returns 404; storage outages remain server errors. The unchanged routes can read existing local files only after confirming that the S3 object is absent and the bucket remains accessible. Task completion still updates the original card and requires durable bytes plus metadata.

## Kafka envelope and ordering

Four topics: `lux.agent.commands.v1`, `lux.agent.events.v1`, `lux.generation.commands.v1`, `lux.generation.events.v1`. Agent keys are `user_id:session_id`; generation keys are `user_id:task_id`. Producers must keep resource ordering and stable event IDs across retries.

```json
{
  "schema_version": 1,
  "event_id": "canonical-uuid",
  "event_type": "RUN_STARTED",
  "user_id": "canonical-uuid",
  "session_id": "canonical-uuid",
  "run_id": "canonical-uuid",
  "task_id": null,
  "sequence_no": 1,
  "occurred_at": "2026-09-23T12:00:00Z",
  "payload": {}
}
```

Incoming Agent events carry a strictly contiguous positive `sequence_no` per run. Incoming connector events carry a monotonic sequence per task; skipped progress is permitted. Commands do not carry this producer sequence. Task versions and session task-update cutoffs are separate Java counters. Task versions also advance for internal lease/recovery writes, so delivered versions need not be consecutive; apply a snapshot only when its version is newer. Java accepts at-least-once delivery; duplicate IDs with changed content are recorded as conflicts.

### Java to Agent

| Event type | Payload and required consumer behavior |
| --- | --- |
| `RUN_START` | `{assistant_message_id,required_task_update_seq,messages:[{id,sequence_no,content}]}`. Messages are only the newly claimed inputs. Deduplicate `run_id` durably and never execute it concurrently. Synchronize task updates through the cutoff before calling a model. |
| `RUN_CANCEL` | Empty payload. Stop/fence the run and emit `RUN_CANCELLED`; a request alone is not confirmation. |
| `RUN_STATUS_QUERY` | `{after_sequence_no}`. Replay all retained canonical events after that sequence, using original IDs and sequences. Report observations using RUN_STATUS if needed. |
| `TASK_UPDATED` | `{task_update_seq,task_id,origin_run_id,tool_message_id,tool_call_id,version,status,assets}`. Persist/deduplicate receipt and apply the newest version at a safe graph writer boundary. |

Python creates the task ToolMessage with `id="task:" + task_id` and the original `tool_call_id`. Each TASK_UPDATED replaces that same ToolMessage through the configured LangGraph message reducer. Events received before the original tool-result binding exists remain pending. Java does not read/modify checkpoints. Python maintains owner/session isolation and a durable applied cutoff; receipt alone is not proof of application. Applying a result does not invoke a model or resume an ended run. If context selection excludes old records, Python must still select the required result/tool-call group before a relevant model call.

### Agent to Java

Supported event types are `RUN_STARTED`, `RUN_WAITING`, `RUN_HEARTBEAT`, `REASONING_SUMMARY`, `TOOL_CALL`, `TOOL_RESULT`, `RUN_STEP`, `TEXT_DELTA`, `RUN_COMPLETED`, `RUN_FAILED`, `RUN_CANCELLED`, and `RUN_STATUS`.

- TEXT_DELTA payload: `{text,block_id?}`; `block_id` defaults to `answer`. Chunks append only after sequence validation. RUN_COMPLETED may provide `{text,block_id?}` as a final replacement for that text block; task blocks remain intact.
- Process events retain their structured payload for the run detail UI. Persist only the reasoning summaries/progress the Agent actually exposes; hidden model reasoning is not a protocol requirement.
- RUN_FAILED may include `{error:"..."}`. Failed and confirmed-cancelled runs preserve queued inputs and pause dispatch. A handled tool error is a TOOL_RESULT or RUN_STEP and need not fail the run.
- RUN_STATUS is an observation, not a terminal transition. Even `{status:"COMPLETED"}` cannot skip missing output/events. The execution owner must replay canonical RUN_COMPLETED/RUN_FAILED/RUN_CANCELLED events to finalize. Unknown status keeps the execution slot reserved. If the original event log is lost, recovery requires establishing that the execution has stopped and producing a fenced terminal event at the next expected sequence; Java does not infer this from elapsed time.

After a terminal event, no new execution events are accepted. An exact repeated event is harmless. A late generation result remains a task update, never a fabricated ReAct event for an ended run.

### Java to connector

SUBMIT payload: `{operation_id,tool_call_id,kind,request}`, with task ID in the envelope. TASK_STATUS_QUERY payload: `{operation_id,provider_task_id?}`. A connector must durably deduplicate submissions and preserve provider correlation before acknowledging. An uncertain provider outcome must be reconciled, never blindly resubmitted.

### Connector to Java

TASK_SUBMITTED and TASK_RUNNING may carry `provider_task_id`. TASK_FAILED carries `{error_message}`. TASK_RESULT carries:

```json
{
  "provider_task_id": "optional-provider-reference",
  "sources": [{"url":"https://source.example/result.png","filename":"result.png","media_type":"image/png"}]
}
```

Sources must be complete HTTP(S) resources returning 200. Redirects are not followed. Java bounds connection/read time, transfer size and total download time; it validates declared Content-Length when present. Result URLs must remain retrievable for import retries. Local HTTP URLs are supported for trusted connector tests; URL fetching is not a public user-input endpoint. A connector that cannot preserve the source must retain/recover the result independently without repeating paid generation.

TASK_STATUS carries `status` UNKNOWN, SUBMITTED, RUNNING, FAILED or SUCCEEDED. SUCCEEDED also requires `sources` and maps to Java IMPORTING. UNKNOWN only updates liveness/sequence. Once IMPORTING, the accepted source set is immutable. Terminal task correction and automatic provider resubmission are outside v1.

## Persistence and recovery

MySQL transactions establish business order. All session mutations first perform an immediate version compare-and-set on the same session row, refresh its state, then read/change child rows. Hibernate version checks protect mutable messages, runs, tasks, and delivery records. Normal reads do not use locking SQL, but updates still hold database write locks through commit. Conflicts retry the entire outer database transaction with fresh state. Kafka and file transfers happen outside those transactions. Outbox publication retries only the earliest undelivered row per topic/key, using the same envelope after uncertain acknowledgment. Java runs one publisher instance in v1.

The Kafka listener first records an inbox entry durably, then applies it in a separate transaction that commits business changes and APPLIED together. A gap/transient failure remains RECEIVED and recoverable even after Kafka offset acknowledgment. Invalid envelopes/ownership/key conflicts become diagnostic rejections; inability to persist receipt makes Kafka retry without dropping the record. No distributed MySQL/Kafka transaction or exactly-once provider call is assumed.

SSE events have `id=sequence_no`, `event=change type`, JSON resource references as data. Types include MESSAGE_CREATED, MESSAGE_CLAIMED, MESSAGE_UPDATED, RUN_CREATED, RUN_EVENT, RUN_FINISHED, RUN_UPDATED, TASK_UPDATED, QUEUE_RESUMED and SESSION_UPDATED. The frontend updates/refetches the referenced object; these events are not chat messages. The user-scoped URL works with native EventSource; use `?after=` for an initial replay cursor, and the browser supplies Last-Event-ID on reconnect. Streams have a 30-minute timeout, 15-second heartbeat and a local 128-connection limit; reconnect using the last processed ID. V1 keeps replay records indefinitely.
