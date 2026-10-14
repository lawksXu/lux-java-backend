# User, session, message, run, and asynchronous result design

Date: 2026-09-23

Status: Consolidated design for review; no runtime implementation is included.

## 1. Scope and decision status

Lux is a local chat application for generating text, images, and videos, with private resources owned by individual users. The first version has linear sessions and user-level data isolation, without registration, login, identity verification, organization/workspace tenancy, or message-history branches. User ownership supersedes the earlier single-user assumption.

The conversation established the following product decisions:

- A `user` owns private resources. User-owned business records carry `user_id`, and backend operations enforce ownership within the current user context. Login and identity verification remain outside scope.
- The frontend displays an ordered list of user and assistant messages inside a session.
- An assistant message is associated with an Agent run, whose execution events appear under that message.
- A session executes at most one run at a time. A dispatch consumes all currently queued user messages as one ordered batch.
- A normal run completion automatically dispatches the next batch. An abnormal ending leaves queued messages unchanged and pauses automatic dispatch.
- An asynchronous generation task has a card in its originating assistant message. Completion updates that card in place, without appending a result message.
- Python owns Agent context. Java sends new inputs and business events, without rebuilding or resending conversation history.
- An asynchronous result replaces the original LangGraph tool message using its stable message ID. This choice was explicitly confirmed on 2026-09-23.

The recovery, ordering, and context-visibility rules below make these decisions implementable. They are explicit design defaults introduced by this consolidation, not claims that every detail was separately approved. Concrete database columns, API paths, broker topics, timeout values, and storage adapters belong to the subsequent implementation design.

## 2. Ownership

| Component | Responsibility |
| --- | --- |
| Java backend | Users, current-user context, resource ownership enforcement, sessions, displayed messages, queued inputs, run scheduling and business status, persisted run events, tasks, assets, frontend notifications, durable business-event publication |
| Python Agent | User-scoped LangGraph execution and checkpoints, model-message history, context selection, tools, applying task updates to model messages, execution-event publication |
| Model connector | External model and generation-provider integration, including provider-specific submission and result retrieval |
| Frontend | Ordered messages, execution details, task-card rendering, and completion notifications |

Java is authoritative for business task status and assets. Python is authoritative for the Agent's execution state and model context. Their stored views have different purposes; neither side overwrites the other's entire history. Kafka is the selected broker, and Redis the selected cache; neither replaces durable business records or checkpoints.

## 3. Logical data model

| Entity | Meaning and relationships |
| --- | --- |
| `user` | Resource owner; minimal fields are `id`, optional `display_name`, and creation/update timestamps; no credentials or login state |
| `session` | Conversation owned by `user_id`; has ordered messages, an active-run reference, and an automatic-dispatch pause flag |
| `message` | One user or assistant item owned by `user_id`; has a stable ID, session ID, immutable sequence number, and content blocks |
| `agent_run` | One Agent execution owned by `user_id` for an ordered batch of user messages; may contain multiple model calls, tool calls, and generation tasks |
| `run_event` | One reported execution event owned by `user_id`, associated with its run and ordered within that run |
| `task` | One asynchronous generation request owned by `user_id`; references its originating session, run, assistant message, and tool invocation |
| `asset` | One stored media result or uploaded input owned by `user_id`; a generated asset references its task, while an upload need not have a task |

`user.id` is the primary identity; every other entity above has a non-null `user_id` referring to it. In `message`, `user_id` means the owner of the conversation resource, including assistant messages. The separate `role` field identifies the speaker. Ownership does not change after creation in this version.

For this first version, one run has one frontend assistant message. That message can contain text and multiple task cards. The run can contain many internal LangGraph assistant and tool messages; those do not each become frontend chat bubbles.

```text
user
  session
    messages, ordered by sequence_no
      user message A ----+
      user message B ----+--> run R input batch
      assistant message ------> run R ----> run events
        text block
        task reference ------------> task T ----> generated assets
      later queued user message
  uploaded assets, which need not belong to a session
```

Queued user messages have no executing run yet. When claimed, they become associated with exactly one run input batch. An assistant message's `run_id` identifies the run that produces it. These are different meanings of the association and must remain explicit in the schema/API.

### Identity rules

| Identifier | Meaning |
| --- | --- |
| `user_id` | Stable resource-owner ID, resolved once for an external request and propagated by the backend |
| `session_id` | Stable conversation ID; the owning `(user_id, session_id)` maps to one canonical LangGraph thread ID |
| Java `message_id` | ID of a frontend user or assistant message |
| `run_id` | ID of one batch execution; never used as the long-lived LangGraph thread ID |
| LangGraph `message.id` | ID used to replace one internal model message |
| `tool_call_id` | Connects an internal assistant tool call to its tool result |
| `task_id` | Business generation-task identity |
| `event_id` / `task.version` | Delivery deduplication identity / monotonic task-snapshot version |

Python retains a durable binding from `task_id` to the original internal tool-message ID and `tool_call_id`. These IDs are not the frontend assistant-message ID. For this version, one generation-tool invocation creates one task, which can return multiple assets.

### Current user without login

Explicit local-development default: the frontend supplies the selected existing user's ID in `/api/v1/users/{user_id}/...`; internal HTTP tools use `/internal/v1/users/{user_id}/...`. Each controller validates the path user and passes its normalized ID to business operations. Malformed or unknown identities are rejected, and missing-user routes have no fallback. The former X-User-Id header and CurrentUser argument resolver are no longer used. Local bootstrap can provision a default user so the application is usable without a registration/login flow. User-management UI is not required by this change.

This is a trusted local identity selector, not proof of identity. Ownership checks isolate operations performed as user A from resources belonging to user B, but a caller who deliberately supplies B's valid identity can act as B. Protection against impersonation requires a future trusted identity source. Resource checks remain mandatory now so that introducing that source later does not require redesigning resource ownership.

Client bodies and LLM tool arguments cannot independently choose or override a resource owner. For a new session/upload, Java sets ownership from the current-user context. For child records, Java derives ownership from the already-validated parent. Internal Agent commands inherit the run's owner. External provider callbacks resolve ownership from Java's stored provider-to-task mapping rather than a callback-supplied user ID.

### Private-resource isolation rules

- Every user-facing list, detail, mutation, batch lookup, search, queue operation, run cancellation, and task/asset access is scoped by the current `user_id`. A lookup uses both owner and resource identity; possession of a resource ID is insufficient. Foreign and missing resources have the same not-found behavior and expose no foreign metadata.
- Persisted child ownership must match its parent. Session-bound message/run/task associations must also refer to the correct session; merely belonging to the same user is insufficient. Referenced input assets must belong to that user; reuse across that user's own sessions is allowed. Uploaded assets retain ownership even without a session or task.
- Database constraints enforce `user_id` references and owner-consistent parent links where applicable; service validation covers multi-record references and content-block asset/task IDs. Queries and updates are owner-scoped even when entity IDs are globally unique. Indexes and uniqueness constraints must support these scoped operations.
- Java-to-Python commands and internal task/run events carry `user_id`. Consumers check the owner against the stored session/run/task binding before updating anything. A mismatched event is rejected and recorded for diagnosis; it never changes ownership or reaches another user's graph or frontend stream.
- LangGraph thread/checkpoint access, any retained Agent memory or retrieval index, task-message bindings, caches, and frontend subscription/replay cursors are scoped to the owning user and session as applicable. Python resolves its internal thread identity from the validated pair; clients do not directly select arbitrary checkpoint IDs. State maintenance and recovery use the same scope.
- Frontend caches include user identity. Switching local users closes the old stream and clears the rendered private view; late responses remain associated with the old user's cache and cannot populate the new view. Server-side stream subscription and replay perform their own ownership checks.
- Asset content, thumbnails, video previews, and downloads use private storage and owner-checked delivery. URLs returned to the frontend identify an access-controlled asset endpoint; media requests must include the same current-user context, including range requests. Raw storage paths and permanent public provider URLs are not substitutes for private asset access. Provider-result URLs remain internal, with results copied into managed private storage before being exposed as available assets.
- Request and operation deduplication is scoped by owner as well as the logical operation. The same client request key from two users must neither collide nor return another user's existing result. Background workers and inbox/outbox records retain the original owner independently of whoever is currently using the frontend.

There is no cross-user resource sharing, ownership transfer, or organization/role permission model in this version. These rules describe application-level isolation under the supplied local user context, not isolation from another process with access to the same local database or files.

## 4. Arrival, ordering, and dispatch

1. Java verifies that the session belongs to the current user, then saves each incoming user message with that owner, a session sequence number, and queued delivery state. An owner-scoped stable client request ID prevents duplicate messages on network retries.
2. When the session has no active run and dispatch is not paused, Java claims all eligible queued messages in one serialized transaction, in sequence order.
3. In that transaction, Java creates the run, records its ordered input IDs, creates its assistant placeholder message, and reserves the session's active-run slot. Dispatch is published durably after this decision is recorded.
4. The assistant placeholder receives its sequence number at creation. Later user inputs receive later numbers. Streaming changes the placeholder's content without moving it.
5. Messages arriving after the claim boundary remain queued for a later run. There is no arbitrary batching delay and no attempt to absorb future arrivals into the claimed batch.
6. Python verifies the command's `user_id` and session/run binding, accepts it idempotently within that owner scope by `run_id`, appends the new user messages once, and starts that run using the existing user/session checkpoint.

Example display and execution order:

```text
1  user: initial request          -> run A
2  assistant: run A placeholder
3  user: queued follow-up B       -> run B
4  user: queued follow-up C       -> run B
5  assistant: run B placeholder   -> created after A completes
```

B and C retain separate message identities and order but are passed to one run together. They are not concatenated into an untraceable string.

There are two serialization responsibilities: Java protects the session's dispatch decision; Python permits only one owner to advance or maintain that session's graph state. A Java transaction alone cannot protect an active Python graph from concurrent checkpoint writes.

## 5. Run completion and the queue

| Outcome | Meaning | Queue behavior |
| --- | --- | --- |
| `COMPLETED` | Agent reached its intended normal endpoint; execution has stopped and its final checkpoint/output are durable | Finalize the assistant message, release the slot, and dispatch all currently queued inputs if any |
| `FAILED` | A non-recoverable error or exhausted retry/limit prevents normal completion, and the execution is known to have stopped | Preserve every queued message unchanged; pause dispatch at session level |
| `CANCELLED` | Cancellation has been confirmed by the execution owner | Preserve every queued message unchanged; pause dispatch at session level |
| Nonterminal or uncertain | Starting, running, waiting, cancellation requested, or execution status cannot yet be established | Keep the slot reserved and reconcile; do not launch an overlapping run |

A handled tool error, a refusal returned normally, or a failed generation task does not automatically fail the run. If the Agent explains the outcome and reaches its normal endpoint, the run can complete normally. Network disconnection is not evidence that a run stopped.

Java must persist the final assistant content and required execution records before treating successful completion as permission to dispatch the next batch. The completion contract needs a final output snapshot and an event-sequence boundary so that a terminal signal arriving before earlier events does not lose the final output.

On abnormal termination, the pause flag is a session change. The queued messages themselves retain their contents, positions, queued states, and unclaimed associations. Inputs already claimed by the failed run remain attached to that run; they are not silently moved back into the queue. The run's assistant message retains any partial output and task cards, with the abnormal outcome shown explicitly.

Explicit default for manual recovery: a user action to continue sends the currently queued batch and clears the pause. With an empty queue, that action simply restores idle automatic dispatch. Sending another ordinary message, reconnecting, or restarting does not clear the pause. Queued messages can be edited or withdrawn before they are claimed; those user-requested actions do not rewrite Agent history because the inputs have not been sent to Python yet. Withdrawal alone does not clear the session pause.

For this version, retrying a terminal run means explicitly resubmitting the desired user input as new message records, with new IDs, and choosing to continue if dispatch is paused. They participate in normal queue ordering; the original records retain their original run association. This is intentional user resubmission, distinct from deduplicating a network retry. It does not silently replay generation side effects. A genuinely new generation request creates a new task; transport retries of an existing request reuse its existing task.

## 6. Asynchronous task and card lifecycle

1. An Agent tool requests task creation from Java using a stable operation ID, along with its session, run, and tool-call identity. Java derives `user_id` from the validated run and verifies ownership of all referenced input assets.
2. Java idempotently persists the task and a task-reference block in the originating assistant message. Repeating the same operation returns the same task and does not resubmit generation.
3. Python records the tool result using a stable internal tool-message ID. Its content initially contains the task identity, accepted status, version, and relevant generation metadata.
4. The connector submits/retrieves the external generation. Java persists each accepted business-state change and resulting asset records.
5. Java publishes a durable `TASK_UPDATED` event. The frontend refreshes the original card; Python updates the original internal tool message.

A frontend task block stores a reference such as:

```json
{"type": "task", "task_id": "task_001"}
```

The card reads current task/asset data. It does not rely on a copied task status frozen inside the assistant's text. Text streaming and task-block insertion use separate block identities/updates so one cannot erase the other.

A task may outlive its originating run. Its later completion does not reopen that run, change that run's terminal status, create a new assistant bubble, start a new run, or resume a paused user queue. If the Agent must use the result to finish the current request, that run remains nonterminal while waiting; receiving the result can resume that same waiting execution.

Explicit default: cancelling a run stops Agent execution but does not imply that already-submitted provider tasks were cancelled. Task cancellation is a separate operation with its own supported/confirmed outcome. Until confirmed, a late successful task result is still recorded and displayed.

The frontend subscribes at user/session scope, including while no run is active. Completion of an offscreen card may produce a lightweight notification with a jump to the original message; it does not insert a new chat message. Reload reconstructs the view from owner-scoped messages, run events, and current task snapshots. A reconnect cursor plus snapshot fallback prevents reliance on an uninterrupted stream.

## 7. Applying task results to LangGraph

### Event content

The logical event includes `event_id`, `user_id`, `session_id`, `task_id`, `origin_run_id`, `version`, status, and result descriptors. A task-update sequence within the owning user's session provides a synchronization cutoff. Result descriptors contain stable asset IDs and metadata; content bytes are not embedded in the transport event. A consumer may retrieve a newer authoritative snapshot within the same owner scope before applying the update.

`origin_run_id` remains the run that created the task, even if another run is active when the result arrives. Python targets the latest canonical state of the session, not an old checkpoint from the originating run.

### Replacement rule

- Validate the event owner and locate the original tool result through the owner-scoped task binding.
- Preserve its internal `message.id`, role, `tool_call_id`, position, and stable submission facts.
- Replace its current task snapshot with the accepted newer version, including success/failure information and asset descriptors.
- Apply through an ID-aware message reducer such as `add_messages` and persist the new checkpoint.
- Repeated or older versions have no additional effect. Original checkpoints and Java execution events retain the earlier observations for audit.

This is a deterministic Python update; the LLM does not edit its own stored context. `task_results` is not a built-in LangGraph field and is not required by this design. A separate task-results map is not a substitute for updating model-visible message content.

LangGraph documents that `add_messages` replaces existing IDs and appends new IDs. Therefore the implementation must verify the target exists; a missing target must not accidentally append an orphaned tool result. See [message reducers](https://docs.langchain.com/oss/python/langgraph/graph-api#working-with-messages-in-graph-state).

### Timing and concurrency

| Session execution state | Applying a result |
| --- | --- |
| Idle with a settled checkpoint | Acquire the Python session writer and apply promptly; no model call is needed |
| Active model/tool call | Persist the event in Python's inbox immediately; the graph owner applies it at the next safe boundary before a subsequent model call |
| Graph waiting for that task | Apply through the owning execution's result-handling path and resume that same run when its dependency is satisfied |
| Original tool result not yet checkpointed | Retain the event until the tool-result binding and message are durable, then apply the newest snapshot |

An external `update_state` call is not a mechanism for hot-reloading an active node's in-memory input. A model request already in flight continues using the input it received. There is no need to wait for an entire run to finish when a safe graph boundary is available.

`update_state` creates a new checkpoint, and its node attribution affects subsequent scheduling. Idle maintenance must preserve the intended settled control state and must not inadvertently schedule the old tool invocation again. See [checkpoint updates](https://docs.langchain.com/oss/python/langgraph/checkpointers#update-state).

## 8. What the next model call can see

Persistence and model visibility are separate guarantees. Python must build the next model request from the updated canonical state and include the relevant result. Updating a custom metadata field or a message excluded from the request does not make the result visible.

For this version, context handling follows these rules:

1. Keep original task tool messages and their matching assistant tool-call groups addressable in persistent graph history. Perform initial context trimming on the model-request view, without deleting these canonical records.
2. Include newly applied terminal task results in the next eligible model request, together with the complete tool-call/result groups needed to form valid history. Do not split a group when one assistant turn contains multiple tool calls.
3. Track result versions actually supplied to a completed model call. Until then, keep those results eligible for inclusion. This is synchronization metadata, not another model-visible result message.
4. When a later question references an older task, Python retrieves the corresponding records/results within the run owner's scope. Asset references from the UI provide explicit IDs where available but never grant ownership by themselves; genuinely ambiguous references require clarification.
5. If required groups exceed the model's context limit, surface a context-limit failure, retain all input/result records, and follow the abnormal-run queue policy. Claimed inputs remain claimed by that run. Do not silently discard the very result needed to answer. Destructive history compaction requires a separate restoration design before it is enabled.

Tool-call/result pairing must survive request trimming, as described in [LangGraph memory management](https://docs.langchain.com/oss/python/langgraph/add-memory#delete-messages).

To close the frontend/Agent timing race, a new run-start command carries a cutoff for task updates already committed in Java at dispatch. Before its first model call, Python applies updates through that cutoff, or reconciles equivalent latest task snapshots. If synchronization is unavailable, the run waits or reports the failure; it does not knowingly answer from the older task version. This transfers synchronization metadata, not conversation history.

During an active run, the graph owner drains received task events at safe boundaries. A result committed after a model request's cutoff is not retroactively part of that request.

Knowing `task_001` succeeded with `asset_001` is different from seeing the image pixels or video contents. Python must use the model's supported media input or an inspection tool when visual understanding is needed. Asset IDs are stable; temporary URLs can be resolved/refreshed. A cloud model cannot be assumed to fetch a local filesystem path or localhost URL.

## 9. Delivery, deduplication, and recovery

| Boundary | Required behavior |
| --- | --- |
| User submission | Owner-scoped stable client request identity prevents duplicate frontend messages |
| Run dispatch | Owner-bound stable `run_id` prevents duplicate concurrent execution of the same run |
| Task creation | Owner-scoped stable operation identity prevents duplicate paid generation submissions |
| Task updates | Stable event ID plus monotonic task version prevents duplicate application and stale regression |
| Run events and text chunks | Stable event ID/sequence permits replay without duplicated process entries or text |
| Task-card creation | Stable block identity/task reference prevents duplicate cards |
| Python state application | Acknowledgement follows durable checkpoint application; a replay after a crash safely observes the already-applied version |

Java persists a business change and its outbound delivery record atomically, then retries publication until acknowledged. Python durably receives events before acknowledging receipt. Receipt and application are distinct milestones; an event stays recoverable until applied. These inbox/outbox responsibilities are delivery infrastructure, not extra frontend message types.

The design targets at-least-once delivery with idempotent effects; it does not assume cross-service exactly-once execution. A provider submission whose outcome is unknown must be reconciled using its request/provider identity before any resubmission. If the provider cannot resolve that uncertainty, mark it for recovery instead of creating a potentially duplicate charged request.

After restart, restore the session pause flag, reconcile active runs, replay outstanding deliveries, and recover task polling. A session with an uncertain old execution remains unavailable for a new run until that owner is known to have stopped or been safely fenced.

An interrupted tool invocation can leave a recorded tool call without its result. Before admitting the next run, Python repairs that pair using the durable task/submission record or an explicit interrupted-tool result. This repair does not reissue a generation merely to reconstruct history. A missing binding is recoverable work, not a reason to silently drop the result event.

## 10. Execution events and frontend rendering

Java persists the execution information actually exposed by the Agent: reported progress, available reasoning summaries, tool calls/results, errors, and lifecycle changes. Each event carries its owner `user_id`, run ID, stable identity, and ordering information; tool-related events also carry `tool_call_id` when applicable. Tool payloads exposed in events follow the same private-asset delivery rules as message content.

Provider-hidden reasoning is not assumed to be accessible. The UI can show reported reasoning summaries and execution traces without promising a verbatim internal chain of thought.

Final response content lives in the assistant message. Execution detail lives under its run events. Task status and media live in task/asset records. The frontend fetches messages first and joins or lazily loads these related records; it need not issue one separate network request per message.

An asynchronous task status change remains a task event after its originating run has ended. It must not be relabeled as a new ReAct step executed by that completed run. Updating the internal tool-message snapshot does not rewrite the original execution audit event.

## 11. Review and acceptance scenarios

These are design acceptance cases. Java automation covers a subset in the unit and container suites; see the README for executed checks and remaining container verification. Python checkpoint/model behavior remains contractual in this iteration.

| Scenario | Expected result |
| --- | --- |
| B and C arrive during run A | On A's normal completion, B and C start one new run in order |
| D arrives after the next batch was claimed | D remains queued for a later run |
| A fails or is cancelled | Queued messages remain unchanged; automatic dispatch pauses |
| A fails after task submission | The task can finish independently; no automatic task resubmission |
| A tool fails but the Agent handles it | Run can complete normally; task and run outcomes remain separate |
| A result arrives after its run completed | Original card and internal tool result update; no extra chat bubble or run |
| A result arrives during a different model call | Inbox persists it; next safe boundary applies it without modifying the in-flight request |
| A result arrives before the initial tool result is durable | Event waits, then replaces the correct original tool message |
| The same event arrives twice, or an older version arrives late | One effective application; no status regression |
| Card completion is visible before the user asks about it | The next run's synchronization cutoff prevents a stale first model call |
| Old task messages are outside the recent context window | Canonical records remain addressable; required complete groups are selected for the request |
| Java/Python restarts between persistence and acknowledgement | Replay converges using stable identities and versions |
| A response stream disconnects | Persisted state and replay/snapshot rebuild the display; no inferred run failure |
| The result card is far above the viewport | Completion notification can navigate to the existing card |
| A request in user A's context references user B's session/message/run/task/asset | Read, mutation, subscription, replay, and download are rejected without exposing B's resource metadata |
| A list or batch lookup runs in A's context | Only A's resources are returned; totals, pagination, and joined records do not reveal B's data |
| A task in A's session references B's asset | Creation fails before provider submission; ownership is not reassigned |
| A forged event pairs A's identity with B's task/session | It is rejected before any business state, checkpoint, or frontend update |
| A and B submit the same client request key | Each receives an independent owner-scoped result |
| Two users have active Agent sessions | Checkpoints, selected history, retrieval, caches, and events remain isolated |
| The frontend switches to B while A's task completes | A's result remains with A; it cannot update B's view or Agent context |
| The local user identity is missing, malformed, or unknown | The request is rejected without implicit fallback or resource creation |

## 12. Explicit boundaries for subsequent work

The Java implementation uses MySQL 8.4/InnoDB, Flyway, Spring Data JPA/Hibernate, version-checked session coordination, a persisted queued-message batch, Kafka inbox/outbox delivery, SSE replay and private S3-compatible asset storage via AWS SDK v2 and local SeaweedFS. The 2026-09-24 persistence migration replaces JdbcClient with feature repositories and scalar-reference entities, preserving message identity and wire formats. Session version compare-and-set happens before queue inspection; mutable records use Hibernate `@Version`, with bounded whole-transaction retries on conflicts. These writes still hold database write locks. Task snapshot versions are monotonic but may have gaps caused by internal recovery writes. The [Java architecture](2026-09-23-java-backend-architecture-design.md) specifies transaction boundaries, and the [wire contract](../../api-contract-v1.md) specifies concrete routes/envelopes. Java sends only new run inputs and the task-update cutoff. Redis is deferred. Deployment is one Java instance with a dedicated private S3 bucket and local staging directory. AssetStore isolates byte access from business metadata; missing objects can fall back to existing local files without migrating or deleting them. HTTP asset identities and original-card updates remain unchanged; see the [local S3 design](2026-09-25-local-s3-storage-design.md).

Python still needs durable checkpoint storage, execution ownership/fencing, its inbox/application watermark and context selection. The original task ToolMessage uses the stable `task:{task_id}` message ID and original tool-call ID. RUN_STATUS_QUERY requires canonical event replay; observational RUN_STATUS alone cannot finalize a run or skip earlier events. Container acceptance and actual Python checkpoint behavior are not implied by the authored Java implementation.

Task-result correction after a terminal result, destructive context compaction, historical message editing, branching, and automatic model calls triggered solely by background completion are outside this first-version design. A new user request or a still-active waiting run can use a completed result normally.
