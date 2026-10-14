# Lux Session Execution Contract v2

Implemented by the Java backend and Python agent. The complete field/invariant inventory is in the [storage design](superpowers/specs/2026-10-09-session-run-turn-item-storage-design.md). This contract requires fresh v2 storage and coordinated consumer activation. Generation plan requests use the typed normalized connector boundary; coordinated generation command consumer activation remains required. Production model and provider-route registrations are empty. No live model/provider integration is asserted.

All resource IDs and request/event IDs are canonical lowercase UUIDs. JSON uses snake_case; ordering/revision counters are positive signed 64-bit integers unless explicitly allowed to start at zero. Dates are UTC timestamps at microsecond precision. Source lifecycle error codes are limited to 64 characters; source/GenerationError messages must fit 65,535 UTF-8 bytes, without silent truncation. Internal APIs use trusted owner paths; these paths are local scope selectors, not authentication.

## Java HTTP

Every route uses an existing `/users/{user_id}` owner. User, Session listing/title updates, uploads and private Asset/content routes retain their existing paths. The execution routes are:

| Method and path | Behavior |
| --- | --- |
| POST `/api/v1/users/{user_id}/sessions/{id}/messages` | Accept immutable input; body `client_request_id`, `content` (ordered text/asset blocks); HTTP 202 `InputAcceptanceResponse` with `item` |
| GET `/api/v1/users/{user_id}/sessions/{id}/items?before=N&limit=50` | Reverse cursor page, returned in ascending registration order, with referenced `runs`, `turns`, `next_before_sequence_no`, `last_event_sequence_no` |
| GET `/api/v1/users/{user_id}/sessions/{id}/items/{item}` | Read one scoped Item projection |
| POST `/api/v1/users/{user_id}/sessions/{id}/stop` | `Idempotency-Key`; HTTP 202 `event_id`; Session control only |
| POST `/api/v1/users/{user_id}/sessions/{id}/continue` | `Idempotency-Key`; HTTP 202 `event_id`; Session wake only |
| GET `/api/v1/users/{user_id}/runs/{id}` | Read observed Run; unknown shell status may be null |
| GET `/api/v1/users/{user_id}/sessions/{id}/execution-events?after=0&limit=100` | Original Python facts in Session sequence order; gaps are possible |
| GET `/api/v1/users/{user_id}/sessions/{id}/events?after=0` | Durable SSE; Last-Event-ID takes precedence on reconnect |
| POST `/internal/v2/users/{user_id}/tasks` | Body `operation_id`, `session_id`, `kind` (IMAGE/VIDEO), legacy opaque connector-owned `request` only; new normalized requests are rejected; HTTP 201 TaskSubmissionReceipt |
| GET `/internal/v2/users/{user_id}/tasks/by-operation/{operation_id}` | Original submission receipt; 404 does not prove external work never happened |
| GET `/internal/v2/users/{user_id}/sessions/{session_id}/tasks/{id}` | Consistent immutable TaskQuerySnapshot for a new get_task call |
| POST `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-tools/{tool_name}` | Strict named `JavaPlanToolInvocation`; HTTP 200 original `JavaPlanToolOperationReceipt`, including proven business rejections |
| GET `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-tool-operations/{operation_id}` | Original Session-scoped plan tool receipt; absent receipt does not establish nonexecution |
| GET `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-context` | Authoritative focus, nullable focused plan snapshot and catalog |
| GET `/api/v1/users/{user_id}/sessions/{session_id}/plans/{id}` | Scoped `GenerationPlanSnapshot` |
| GET `/api/v1/users/{user_id}/sessions/{session_id}/plans?limit=20` | `GenerationPlanReadResult`; descending creation cursor using paired `beforeCreatedAt`/`beforeId`, limit 1–50 |
| GET `/api/v1/users/{user_id}/sessions/{session_id}/plan-focus` | Current `SessionPlanFocus` |
| GET `/internal/v2/users/{owner}/sessions/{session}/inputs?after_input_sequence_no=0&through_input_sequence_no=N&limit=100` | Original retained InputAccepted envelopes, bounded ascending input sequence |

Message content is `[{"type":"text","text":"..."},{"type":"asset","asset_id":"..."}]`. Asset references require ownership. Blocks have no ID. Accepted input shape is `id`, `sequence_no`, `input_sequence_no`, `source`, `kind`, `content`. It contains no Run/Turn and remains independent after incorporation. HTTP idempotency lives solely in `http_request`; reusing a key with different request content conflicts.

Old message-list/edit/withdrawal, Run start/stop and Run event routes are removed. Java never responds with `dispatched_run_id`, selects an active Run or leases an input batch.

## Generation plan tools

The active tool names are `get_model_catalog`, `read_generation_plan`, `create_generation_plan`, `update_generation_plan`, `change_generation_target`, `clone_generation_plan`, `focus_generation_plan` and `submit_generation_plan`. Their complete typed arguments/results are defined by the [generation plan schema](fixtures/generation-plan-v1/contract.schema.json) and [contract](superpowers/specs/2026-10-12-generation-plan-contract-design.md). Production model catalogs and execution/result-verifier registrations are empty. Tests register synthetic capabilities/routes only; these do not register a real provider or make a production draft submittable.

Invocation fields are `type=generation_plan_tool_invocation`, `operation_id`, `session_id`, `tool_name`, `arguments`. The path Session/tool must match the envelope. `operation_id` is the original TOOL_CALL Item UUID, independent of provider `call_id`. Required nullable fields must be present and unknown properties are rejected. The response has `type=generation_plan_tool_operation_receipt`, `schema_version=1`, the same operation/Session/tool identities, the complete owner/invocation `request_hash` and one typed `result`. Valid business rejection is HTTP 200 with `business_execution=NOT_PERFORMED`; malformed envelopes and identity conflicts use 4xx, while technical failures remain uncertain. Original receipt replay precedes fresh CAS, capability and inspection checks and retains historical read observations.

Plans use DRAFT/FROZEN and plan revisions; Session focus has its own revision. Focus-dependent mutations require the focused source and current expected revisions. First accepted submission freezes the single-file normalized request and requested count, then atomically persists N independent child Tasks, N Outbox commands, notifications and one parent receipt. `PlanTaskSubmissionReceipt.tasks` maps contiguous child indexes to distinct Task/child-operation UUIDs; parent identity is not reused as a child operation. Frozen reuse under a new parent identity creates the same count with its unchanged pinned request; clone and cross-media changes create a draft. Context/frontend reads assemble focus, plan and metadata validation in one read-only repeatable-read snapshot, using cached verified media facts and availability without process or storage IO in the transaction.

New normalized requests are accepted only through plan submission. The existing direct Task endpoint retains legacy request handling and original legacy-operation recovery; decoding stored normalized Tasks remains supported.

## Agent Kafka

Commands: `lux.agent.commands.v2`; facts: `lux.agent.events.v2`. Key: `{user_id}:{session_id}`. Schema version: 2. Complete UTF-8 envelopes must fit 31 MiB; v2 topics and both clients use 32 MiB request/record/partition-fetch capacity, with 64 MiB aggregate fetch capacity. Existing broker topics require matching configuration. Oversized Java submissions fail before business acceptance commits. A model adapter must honor the complete fact budget; oversized completion preserves its original receipt/checkpoint intent and blocks materialization before tools, without dropping content or repeating execution. Raising the budget requires coordinated broker/client limits. Each envelope has `schema_version`, `event_id`, `event_type`, `occurred_at`, `user_id`, `session_id`.

| Command event_type | Additional members |
| --- | --- |
| INPUT_ACCEPTED | `item`; `event_id` equals Item.id |
| STOP_SESSION | None; no run_id or input cutoff |
| CONTINUE_SESSION | None |
| QUERY_SESSION_EXECUTION | `limit`; requeues existing facts, creates no lifecycle facts |
| REPLAY_SESSION_EVENTS | `after_event_seq`, `through_event_seq`, `limit`; replay original events only |

Python admits inputs and controls to its durable Inbox before committing Kafka offsets. Its first-receipt ordinal determines stop/wake order. Input context follows `input_sequence_no`. Duplicate commands cannot target a later Run. Stop completes the started Turn and preserves pending inputs; later input/Task notification/continue creates a fresh Run after the old terminal event is durably retained.

Execution facts add `event_seq` and one typed `body`. The outer event_type selects the body; there is no redundant body discriminator:

| Fact event_type | Body |
| --- | --- |
| RUN_SNAPSHOT | `run`, `source_revision`, `status`, `started_at`, `finished_at`, `final_item`, `error_code`, `error_message` |
| TURN_SNAPSHOT | `turn`, `source_revision`, `status`, lifecycle timestamps and error fields |
| AGENT_ITEM_SNAPSHOT | `item`, `source`, `source_revision`, `kind`, `status`, `payload`, `completed_at`, `call_id`, `tool_name` |
| MODEL_RESPONSE_COMPLETED | `model_execution_id`, `turn`, `items` (complete AgentItemSnapshot array) |
| ITEM_TEXT_DELTA | `item`, `stream_seq`, `text` |

RunIdentity is `{id, run_index}`. TurnIdentity is `{id, turn_index, run}`. AgentItemIdentity is `{id, item_index, turn}`. References carry real source identities, so Java can create nullable identity shells before parent snapshots arrive. No identity is inferred from a call_id alone.

Run statuses: RUNNING, COMPLETED, FAILED, CANCELLED. Turn statuses: PREPARED, RUNNING, INTERRUPTED, COMPLETED, FAILED, CANCELLED. Agent Item statuses: PARTIAL, COMPLETED, INTERRUPTED. A completed Run carries Python's explicit final MESSAGE identity. Source revisions belong to individual complete snapshots; Session event_seq belongs to the canonical log and spans Runs. Neither is the Java SSE cursor.

Equal-revision snapshots must have identical source fields. Old snapshots cannot replace newer state. Events preserve identity and original JSON values when replayed; transport deduplication compares canonical sorted-key JSON digests. Complete snapshots project independently of global gaps. Java retains unresolved genuine references for later validation.

## Item payloads and streaming

| kind | Named payload |
| --- | --- |
| MESSAGE / REASONING_SUMMARY | OrderedContent: `blocks` of Text or Asset reference |
| TOOL_CALL + submit_generation | SubmitGenerationCallContent: `arguments` with `kind`, opaque connector-owned `request` |
| TOOL_CALL + plan tool | Named typed plan-call content containing the original `arguments`; operation/Session metadata is added only to transport |
| TOOL_CALL + get_task | GetTaskCallContent: `arguments` with `task_id` |
| TOOL_RESULT | TaskSubmissionReceipt, TaskQuerySnapshot, ToolError or typed GenerationPlanToolResult |
| TASK_EVENT | TaskEventContent: only `task_id`; source SYSTEM, independent Session input |

Tool results use `type=task_submission_receipt` (`task_id`, `accepted`), `type=task_query_snapshot` (`task_id`, `version`, `status`, `assets`, `failure`) or `type=tool_error` (`code`, `message`). Asset references in a Task snapshot contain `asset_id` and the actual `source_index`. Failure contains `stage`, `code`, `message`, or is null. Both TOOL_CALL and TOOL_RESULT carry the exact original provider call_id; only calls carry tool_name. The submit operation_id is the original call Item.id, and is retained in the request/Task/execution receipt, not another Item column.

PARTIAL starts with source_revision=1 and `blocks=[]`. Each consecutive delta appends one text block without merging/trimming fragments. `item_id + stream_seq` deduplicates; temporary blocks.length is the contiguous prefix, without a persisted coverage field. Gaps buffer on that Item only.

Python saves the original full model response receipt after callback drain, checkpoints stable final Item bindings and the response event intent, then materializes the canonical event before tools execute. The completion bundle reuses Item IDs and replaces their whole payloads. Unused live candidates emit nothing. Unsupported live forms bind only in the durable full response. Finalized Items ignore all late deltas. Completing a model response does not complete the Turn or Run.

## Java SSE and presentation

SSE `id` is the Session notification sequence. It is allocated under the Session row lock, with current-read allocation and the insertion in one transaction. Do not substitute Python event_seq or an Item sequence.

`ITEM_CREATED`, `ITEM_TEXT_DELTA`, `ITEM_CONTENT_REPLACED` payloads use SessionItemNotification: `item_id`, `item_sequence_no`, typed `body`. A replacement body is `{snapshot: AgentItemSnapshot}`. Other notifications are SESSION_UPDATED (`title`), RUN_UPDATED (`run_id`), TURN_UPDATED (`turn_id`), TASK_UPDATED (`task_id`), PLAN_UPDATED (`plan_id`, `revision`) and PLAN_FOCUS_CHANGED (`focus`). Notification, projection and transport APPLIED marker commit together where applicable.

The initial page and SSE high watermark share one consistent snapshot. Replay after that watermark. Apply immediate deltas; replace complete payload/status/revision atomically and ignore post-terminal deltas. Keep each Item at its immutable registration position. Independent inputs split a Run into visible segments; never move later new output in front of an intervening input. Subsequent refreshes do not advance an already active SSE cursor past unseen events.

## Python source synchronization

GET `/internal/v2/users/{owner}/sessions/{session}/execution?after_event_seq=0&through_event_seq=N&limit=100` returns SessionExecutionSync: `user_id`, `session_id`, `event_high_watermark`, `events`, `runs`, `turns`, `items`, `next_after_event_seq`. Event pages are bounded original flat envelopes; snapshots are latest canonical source entities, including complete response bundle members. Private context and unobserved candidates are excluded. The initial local implementation returns the full retained entity set; it does not implement snapshot pagination/retention.

Java may apply snapshots immediately, then request missing original event ranges. A source high watermark does not prove Java has retained earlier events. Observation/replay never closes a Run, invents an event or issues a model request. Configure `LUX_AGENT_BASE_URL` to enable proactive Java observation.

## Generation callback and acknowledgment

Java v2 submission is a flat envelope on `lux.generation.commands.v2`, key `{user_id}:{task_id}`, event_type SUBMIT, adding `task_id`, `operation_id`, `kind`, `request`. It has no fabricated Run/call reference. Task status queries use TASK_STATUS_QUERY plus `last_observed_sequence_no`. Plan children use `request_type=image_generation_plan_v1` or `video_generation_plan_v1`, `schema_version=1` and the frozen provider binding; the surrounding Kafka envelope remains schema version 2. Each normalized request expects exactly one file with auxiliary outputs disabled. Legacy payloads remain independently decodable; normalized routing never falls back to a legacy provider path.

Generation observations on `lux.generation.events.v2` add `task_id`, `sequence_no`, `connector_status`, `outcome`. Connector statuses are ACCEPTED, RUNNING, SUCCEEDED, FAILED. Nonterminal outcome is null. Terminal outcome is named SUCCEEDED with ordered `sources` (`url`, `filename`, `media_type`), or FAILED with `error` (`code`, `stage`, `message`, optional provider diagnostic). SUCCEEDED means downloadable sources exist; Java remains IMPORTING until Assets are verified.

Java also reads `lux.generation.events.v1` through a separate declared adapter. Legacy SUBMITTED means provider-acknowledged RUNNING; UNKNOWN is an observation, not a fifth status. Topic and schema version must agree. The connector's provider task IDs remain opaque diagnostics rather than Task columns.

The receiving Kafka worker saves Inbox and manifest, downloads outside transactions, then saves Task terminal state, one Task notification input, Outbox, SSE and Inbox APPLIED before RECORD acknowledgment. Max poll records is one; committed offset is a completed partition prefix. Confirmed source failure persists IMPORT failure before ACK. Uncertain publication/database/interruption throws for redelivery using the same Task/source/Asset identities. Generic Inbox drain never imports generation callbacks. No Task retry, importer lease, fixed importer worker or polling scanner is introduced.

Shared [v2 fixtures](fixtures/v2/) cover Unicode, whitespace, nulls, identity, timestamps and canonical digests in both languages. Java tests use contract doubles for Kafka workflows; Python tests use native checkpoints and injected adapters. Live cross-service/provider acceptance remains a separate verification step.
