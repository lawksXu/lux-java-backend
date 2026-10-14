# Explicit Data Structures

The user approved the boundary principle in the current conversation and requested implementation across existing Java code after updating all four Lux repository guides.

## Scope and constraints

- Add the same English explicit-structure policy to the existing root AGENTS.md in all four Lux repositories. Do not create a parallel AGENT.md or alter unrelated guidance.
- Refactor Java internal business contracts, including message content, event envelopes and interpreted payloads, task results, and fixed-shape commands, notifications, responses, and hash inputs.
- Use Java 17 records/classes and typed variants; retain Chinese explanatory Java comments. Do not use Java var, schema-shaped maps, Object, or Any as substitutes for known structures.
- Keep genuinely dynamic connector request parameters and uninterpreted process-event details explicitly documented and contained. Raw Inbox receipt storage and generic JSON serialization/canonicalization remain boundary concerns.
- Keep existing HTTP/Kafka JSON names, optional-field semantics, database JSON formats, hash/idempotency semantics, event sequencing, ownership checks, locking, retry classification, and transactional behavior.
- Add no dependencies, database migrations, Git branches, or commits. Python implementation changes are outside scope.

## Design

Use explicit models owned by their feature: chat content blocks and notification payloads in chat; generation result sources and connector messages in task; shared protocol envelopes and parsing in infrastructure. Parse and validate incoming transport data at its adapter boundary. Internal services and repositories should pass typed business values. JSON trees may remain in ingestion, serialization, persisted raw evidence, and documented dynamic fields only.

Prefer a typed variant for distinct content or event forms instead of a broad record filled with unrelated optional fields. Avoid building a general schema framework. Generic JSON codecs may accept arbitrary serializable values because they do not represent a particular business structure.

Maintain wire contracts with Jackson naming/type annotations and focused transport conversion where needed. In particular, distinguish absent fields from explicitly null fields when hashing or matching existing payloads. Preserve raw archival evidence for duplicate/conflict decisions, even if a typed projection is used for business processing.

## Validation

Capture the current Maven verification result before code edits. Protect real compatibility risks: snake_case names, discriminator fields, missing versus null fields, persisted JSON round trips, idempotent response replay, conflicting events, ownership checks, invalid content, and task result imports. Adapt existing behavior tests to typed interfaces rather than deleting scenarios. Run focused checks during implementation, then ./mvnw verify. Report skipped Docker-dependent acceptance separately.

## Implemented boundaries

- `chat.ContentBlock` exposes explicit text, asset and task variants; services pass `List<ContentBlock>`. Its JSON adapter retains unknown metadata and optional-field presence solely for compatible storage and hashing.
- `task.ResultSource` exposes named download fields; repositories and import services pass `List<ResultSource>` while preserving unknown connector metadata at serialization.
- `task.ConnectorRequest` keeps provider parameters opaque and exposes immutable parsed resource references for Java ownership validation.
- `infrastructure.EventProtocol` converts durable raw Inbox records to typed `IncomingEvent` headers and payload variants before service dispatch. Original payloads remain archival/conflict evidence.
- `infrastructure.CommandPayload` and `chat.SessionChange` model known command and notification bodies. `HttpIdempotency` uses a typed response token to restore saved responses.
- Remaining maps represent actual key-to-value associations or SDK adapter requirements; generic JSON codec operations do not define business contracts.

## Compatibility verification

Known source fields are strict at the event ingress boundary. The persisted result-source adapter additionally projects numeric and boolean filenames that the previous implementation accepted, retaining their original JSON representation. This prevents historical tasks from becoming unreadable during the internal type migration. Tests cover both repository reads and actual local HTTP result imports.

The final non-container Maven verification passed 112 tests and packaged the application. Docker-dependent verification remains unverified because both the baseline and changed checkout stalled starting Testcontainers Ryuk; the changed checkout attempt was bounded to 120 seconds. No database migration or external service change was made.

Independent re-review approved the compatibility fix with no blocking findings. The original unknown-source rejection was also retained in Inbox dispatch; all 16 final focused protocol/infrastructure tests passed after that correction.
