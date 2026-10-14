# Generation Plan Contract Design

Java owns durable generation intentions, editable plans, model capability policy and validation. A plan freezes when its first submission is accepted and remains reusable: each submission creates the requested number of independent single-file Tasks using the same frozen configuration. Python owns conversational execution and calls typed Java tools; the model connector translates normalized requests into provider requests and executes them.

The v1 typed contract, eight Java plan tool routes, plan/focus persistence, original receipts and atomic single-file Task batching are implemented. Production model catalogs and execution/result-verifier registrations remain empty; synthetic test registrations do not establish real provider support. This specification describes the approved contract and does not itself deploy migrations or activate providers.

## Contract artifacts

- [JSON Schema](../../fixtures/generation-plan-v1/contract.schema.json) defines every named structure, discriminator, primitive constraint, tool invocation and result using JSON Schema 2020-12.
- [Structural examples](../../fixtures/generation-plan-v1/examples.json) include all eight tool invocations, draft/frozen plans, complete image/video requests and an empty catalog. UUIDs, the `unregistered-schema-example` model ID and provider-binding values are unregistered structural placeholders, not model or adapter registrations. These examples establish shape, not successful business acceptance.
- [Execution contract](../../api-contract-v2.md) documents the active plan routes, context reads, receipts and legacy/normalized request boundaries.

Java should represent records and tagged unions as explicit records/classes and sealed variants. Python should use matching explicit Pydantic models. A known business structure must not become `JsonNode`, `Map<String, Object>`, `Any` or a string-key parameter dictionary. JSON Schema property maps are schema notation, not business parameter maps.

## Ownership and invariants

1. One Session owns zero or more plans and has zero or one focused plan. Focus is business interaction state, not a Run scheduling slot.
2. Plan and Task retain direct owner and Session identities. Neither depends on Run, Turn or Item lifetimes. Original call Item IDs can be operation identities without foreign keys.
3. `DRAFT` permits explicit edits. `FROZEN` forbids changes to the entire plan configuration, intent, parameter origins and frozen request.
4. First successful Java submission acceptance freezes the plan once in the same transaction as all requested Tasks, their Outbox envelopes, the parent operation receipt and frontend notifications. Acceptance is all or nothing; provider acceptance or completion is not the freeze boundary.
5. One frozen plan can create many Tasks. There is no `submitted_task_id` on a plan. Task carries `plan_id` and its own immutable request payload.
6. Replaying one operation returns its original Task list/result. A new generation from a frozen plan uses a new parent operation ID and creates a new Task list using the frozen requested file count; it is not a retry of previous Tasks.
7. Same-media model/profile changes edit a draft. Cross-media changes create a new draft and leave the source untouched. Editing a frozen plan starts with an explicit clone.
8. A model change may clear incompatible execution parameters but cannot silently change user requirements. Unresolved requirements block submission.
9. Java applies explicit catalog defaults only to unconstrained missing fields. Defaults and model-visible snapshots are durable facts, not reconstructed from conversation summaries.
10. Java validates structural and domain facts. It cannot prove that an LLM faithfully extracted natural-language intent. `EXPLICIT` means supplied by a tool, not proof of user approval.

## Wire conventions

Object fields are required on the wire, including nullable fields, except the additive `ReferenceMediaLimit.aspect_ratio_range` capability. That field is omitted for older/unconstrained profiles and cannot be explicitly null when supplied. Unknown properties are rejected. Lists are ordered; duplicate identities and cross-field invariants are checked by Java. Java and Agent readers must be upgraded together before publishing the extended capability; old archived values retain their original serialization.

Identifiers are canonical lowercase UUID strings. Revisions are signed 64-bit integers: plan and capability revisions start at one; focus revision starts at zero. A revision must not overflow. UTC timestamps end in `Z` and have at most six fractional digits. Java uses explicit `String`/UUID, `long` and `Instant` types as appropriate; it must not use `var`.

Nonblank text is additionally checked by Java. Descriptions and prompts are limited to 64,000 Unicode code points and 262,144 UTF-8 bytes at the platform boundary; a profile can impose a smaller prompt byte limit. Identifiers for models are at most 255 characters; profile, preset and resolution identifiers are at most 64. These are Lux limits, not claims about provider capabilities.

Input array order, supplied false values, empty arrays and null remain meaningful. Java normalizes ratios for stored business values, but operation identity still includes the complete original typed arguments; changing an operation's arguments is a conflict even if two inputs normalize to the same value.

## Plan and focus structures

### GenerationPlan

| Field | Type | Meaning |
| --- | --- | --- |
| `type` | constant `generation_plan` | Wire discriminator |
| `schema_version` | constant `1` | Plan contract version |
| `id`, `user_id`, `session_id` | UUID | Stable identity and direct ownership |
| `revision` | positive int64 | Business CAS revision, independent of ORM/source/SSE versions |
| `state` | `DRAFT` or `FROZEN` | Editability of the configuration |
| `configuration` | `ImagePlanConfiguration` or `VideoPlanConfiguration` | Typed intent, model and current parameters |
| `frozen_request` | `NormalizedGenerationRequest` or null | Complete immutable request fixed at first acceptance |
| `created_at`, `updated_at` | UTC Instant | Java record times |
| `frozen_at` | UTC Instant or null | First acceptance time |

`DRAFT` requires null `frozen_request` and `frozen_at`. `FROZEN` requires both; the single-file request must exactly reflect the execution configuration, while requested file count remains frozen in the intent. There is no persisted `READY`, running, completed or failed state on a reusable plan. Task owns execution status.

`SessionPlanFocus` contains `session_id`, nullable `active_plan_id` and `revision`. Focus revision advances only when the pointer changes. Background Task updates, ordinary Session messages and plan parameter changes do not advance it.

Creation, clone and cross-media creation focus the new plan atomically. A focus operation can select a draft or frozen plan. Task completion never changes focus. Reads may inspect nonfocused plans; mutations other than explicit focus must target the focused source plan and carry its observed focus revision.

### Configuration, intent and requirements

Both configuration variants contain `model_selection`, `intent`, `parameters` and `parameter_origins`; their `type` discriminators identify image versus video. `model_selection` may be null while collecting input.

`ModelSelection` contains `model_id`, `profile_id`, `capability_revision`. Java owns the revision. A tool chooses a `ModelChoice(model_id, profile_id, expected_capability_revision)` from a catalog observation; Java rejects a stale choice rather than silently using a new capability version. ModelChoice, ModelSelection and every tool argument remain unchanged; tools cannot supply or patch provider binding. Java derives it from the selected model and exact capability revision.

`ImageIntent` / `VideoIntent` contains a nonblank `description` and matching `ImageRequirements` / `VideoRequirements`. Description preserves the natural-language goal, including style and content that are not interpreted by Java. Known numerical and reference constraints belong in the typed requirement fields.

| Requirement | Image | Video | Null semantics |
| --- | --- | --- | --- |
| `generation_mode` | `IMAGE` | `VIDEO` | No explicitly required output media type |
| `prompt` | text | text | No exact prompt text requirement |
| `size` | `DraftOutputSize` | `DraftOutputSize` | No size requirement |
| `requested_file_count` | positive int32 | positive int32 | Request one file |
| `references` | `ReferenceAsset` array | `ReferenceAsset` array | No exact reference-list requirement |
| `output_format` | `PNG`, `JPEG` | `MP4`, `MOV` | No encoding requirement |
| `duration_seconds` | absent | positive int32 | No duration requirement |
| `generate_audio` | absent | boolean | No audio requirement |

An exact prompt requirement is used only when literal prompt preservation is intended; otherwise description expresses the semantic goal. Java compares exact prompt requirements without silently trimming or rewriting them.

Requirements may be well formed but unsupported by the selected model or current platform submission policy. Such requirements remain stored and produce submission blockers. An empty requirement reference array explicitly requests no references; null imposes no exact list requirement. A nonnull array requires the same ordered asset ID list. False is an explicit requirement, not absence. `generation_mode` describes only the output media type; it does not infer an input scenario.

### ImageParameters and VideoParameters

Execution candidates use the same prompt, size, reference and output-format fields. Video adds duration and `generate_audio`, which controls an audio track inside the same output video, not a separate file. There is no image duration field. Candidate scalar fields can be null in a draft; candidate reference arrays are always present and default to empty. These represent the current choices, not necessarily the user's requirements. `requested_file_count` is a submission requirement, not an execution parameter, model default or parameter-origin field.

An empty candidate reference array always means no currently selected references, including after SET `[]` or CLEAR. It is treated as a missing selection during normalization: a supported nonempty reference requirement can populate it. To require generation without references, the tool must SET the requirement reference list to `[]`; clearing candidates alone cannot express that requirement. With no reference requirement, an empty candidate can satisfy a profile that permits no references.

`ParameterOrigin(field, source, source_model)` records each nonnull scalar/component and each nonempty reference list exactly once. `source=EXPLICIT` requires null `source_model`. Java-applied `MODEL_DEFAULT` requires the exact `ModelSelection` that supplied it. No parameter origin is stored for a missing value or empty reference list. Ratio and resolution have separate parameter origin entries. Derived preset IDs have no independent parameter origin. Tools cannot submit parameter origins.

Compatible parameters retained after a model change retain their original parameter origin. An old model's retained default is an existing choice; the result must not describe it as a newly applied target-model default.

### Size and requested file count

`DraftOutputSize` is a closed union:

- `RatioSize(type=ratio_size, aspect_ratio?, resolution_id?)`: permits partial collection. `AspectRatio(width_units, height_units)` uses positive int32 units; Java reduces them by GCD.
- `PixelSize(type=pixel_size, dimensions)`: `PixelDimensions(width, height)` requires both positive int32 values.

The variants are exclusive. Replacing size replaces the whole size object; it never merges pixel and ratio fields. A model resolution ID is a catalog key, not a universal interpretation of labels such as 2K.

V1 image execution supports listed size presets and explicitly supported custom pixel sizes. V1 video execution supports listed size presets and positive whole-second duration; frame-count and automatic-duration provider options are outside this normalized contract.

Java resolves a complete ratio/resolution pair to exactly one `SizePreset` in the selected profile. Each preset defines `preset_id`, ratio, resolution ID and concrete pixel dimensions. Verified preset registrations require dimensions and consistent ratios; an unknown mapping cannot become an unrestricted preset. Custom pixel rules define explicit bounds, dimension steps, pixel-area limit and allowed ratios. Use checked 64-bit arithmetic for area and duration aggregates.

`requested_file_count` is a nullable positive int32 requirement. Null has an effective value of one. A value greater than one represents an explicit user request for multiple files; the agent cannot invent it, obtain it from a model default or issue extra submissions to fill a count implicitly. Java creates one Task per requested file, and each Task requests exactly one output file. The explicit count remains a requirement even when it exceeds current policy and blocks submission.

The count is frozen with the plan intent/configuration at first acceptance. Reusing a FROZEN plan creates the same effective count of new Tasks; changing that count requires cloning and editing the draft. Null and explicit one retain their original wire meanings but both yield one Task. No automatic replenishment occurs when a Task fails.

### Reference assets

`ReferenceAsset(type=reference_asset, asset_id)` is the single reference structure for all non-text inputs to image and video plans. Java uses trusted metadata to determine actual `IMAGE`, `VIDEO` or `AUDIO` media type and detected MIME, not a model-supplied role. Reference order is preserved; duplicate `asset_id` values are rejected. References are limited to 64 at the Lux boundary and further restricted by the selected profile. There are no role, frame-position, arbitrary URL, local path, provider task or credential fields in these tools.

Input combinations are defined by explicit profiles and media limits. The connector may use a verified, fixed provider mapping owned by the selected profile adapter, for example when a profile permits one image reference and the adapter maps it to a specific provider input field. Capability evidence records that verification. Neither Java nor the connector may guess first/last-frame assignment solely from generic array order or make a role decision that requires missing user input. A provider path requiring the user to distinguish frame roles that this contract cannot express remains unavailable; unsupported media types or combinations remain blockers.

Java obtains facts from a trusted inspection boundary, using the named `VerifiedImageMetadata`, `VerifiedVideoMetadata` and `VerifiedAudioMetadata` variants. They bind asset ID, actual checksum, byte size, detected MIME and kind; image/video includes dimensions, video/audio includes duration, and video includes audio presence. Facts must match the immutable asset checksum. Model-supplied facts and upload-declared MIME are not sufficient evidence.

## Model capability structures

`ModelCatalogSnapshot(type=model_catalog_snapshot, catalog_revision, submission_policy, models)` contains platform submission policy and image/video `ModelDefinition` variants. Each definition specifies `model_id`, display name, positive capability revision, product `enabled` flag, required `provider_binding: ProviderBinding` and matching profile array. Both `ImageModelDefinition` and `VideoModelDefinition` require that binding. The catalog key is `(media_type, model_id)`; the same ID can have distinct image and video definitions. Revisions identify immutable content, including defaults and provider binding. Changing a binding advances both capability and catalog revisions.

`ProviderBinding(provider_id, provider_model_id, adapter_id)` contains three required, nonnull, nonblank `String` fields. They identify a verified execution route, not credentials or an endpoint.

| Field | Lux maximum length | Meaning |
| --- | --- | --- |
| `provider_id` | 64 | Provider registry key |
| `provider_model_id` | 255 | Opaque actual third-party model/deployment ID; no guessing or alias resolution |
| `adapter_id` | 128 | Versioned interface-adapter registry key; its version is included in the key |

There is no separate adapter revision field. The contract version named by one adapter ID is immutable: it must not be changed in place to use a different provider, API or field mapping. A changed adapter contract requires a new versioned ID and revised binding. Java validates that the binding fits the model media type, selected profile, request discriminator and schema version. The connector owns registered adapter implementations, provider accounts/credentials, actual endpoints, HTTP transport, field mapping, Asset resolution and result verification. None of those implementation details becomes a plan-tool argument. This design registers no concrete provider/model binding or adapter.

The first implementation stores definitions as typed Java code. Java owns the product's allowed subset; connector code owns provider translation. Cross-language fixtures and explicit evidence keep them consistent. No capability or default values are registered in this specification.

`GenerationSubmissionPolicy(maximum_tasks_per_submission)` contains a positive int32 platform limit, separate from model capabilities and defaults. Java compares the effective `requested_file_count` with this limit for every new submission, including frozen reuse. An excessive requirement can be saved but produces `REQUESTED_FILE_COUNT_UNSUPPORTED` on `REQUESTED_FILE_COUNT` and blocks submission; Java must not reduce it silently. The example policy value is illustrative and does not register active service configuration.

### Profiles instead of independent capability arrays

A profile is one legal input scenario. It binds output media type, explicit input-media combinations, reference rules and output choices. `generation_mode` is `IMAGE` or `VIDEO`, never an input-to-output taxonomy. A model supporting a duration, a resolution and references independently does not prove it supports their combination. When a rule depends on an input scenario, use distinct explicit profiles rather than a general expression language or hidden Java-only predicate.

| Verified profile field | Image | Video |
| --- | --- | --- |
| `profile_id`, `generation_mode` | required | required |
| `prompt_rules` | max UTF-8 bytes | max UTF-8 bytes |
| `size_rules` | presets, optional custom pixel rules | presets; custom pixels must be null in v1 |
| output options | supported sizes within this profile | `VideoOutputOption(size_preset_id, duration_seconds)` |
| `reference_rules` | required | required |
| `output_formats` | nonempty PNG/JPEG subset | nonempty MP4/MOV subset |
| audio track | absent | explicit nonempty `generate_audio_values` boolean list |
| duration tolerance | absent | nonnegative milliseconds for result verification |
| `defaults` | `ImageDefaults` | `VideoDefaults` |
| `evidence` | nonempty `CapabilityEvidence` array | same |

`PositiveIntDomain` is `DiscreteIntDomain(values)` or `SteppedIntDomain(minimum, maximum, step)`. Values are positive and strictly increasing; range minimum must not exceed maximum and step is positive. Membership is `minimum <= n <= maximum` and `(n-minimum) % step == 0`. Video duration options use this domain; requested file count is not a model capability domain.

`ReferenceRules` defines total minimum/maximum counts, `media_limits`, aggregate video/audio duration limits and an optional input-video-plus-output-duration limit. Each `ReferenceMediaLimit` defines `media_type: ReferenceMediaType` (`IMAGE`, `VIDEO` or `AUDIO`), `minimum_count`, `maximum_count`, `mime_types`, `max_asset_bytes`, nullable `dimensions` and nullable `max_duration_milliseconds`. Each media type occurs at most once. Missing media entries mean unsupported media types. `AUDIO` requires `dimensions=null`; `IMAGE` requires `max_duration_milliseconds=null`. Java validates these fields against the trusted metadata variant. Required media counts must coexist within total limits. There are no role limits, forbidden role pairs or first/last-frame matching rules. A null optional limit in a verified profile means that this product profile adds no such limit; it never represents an unknown provider limit. Incomplete support evidence requires an unavailable profile.

`ReferenceMediaLimit.aspect_ratio_range`, when supplied, is `AspectRatioRange(minimum: AspectRatio, maximum: AspectRatio)`, an inclusive rational range checked with integer cross multiplication. Minimum must not exceed maximum; AUDIO cannot carry a ratio range. Omission adds no constraint and preserves old JSON receipts. Seedance's initial image reference range is minimum2:5 and maximum5:2, applied to trusted actual Asset dimensions before plan freeze/Task creation, and checked again by Connector.

`CapabilityEvidence(source_kind, reference, checked_at, note)` separates official documentation, contract verification and product policy. A policy may narrow a verified provider range; it cannot establish unverified provider support. Provider account access and runtime failures remain execution outcomes.

`UnavailableImageProfile` / `UnavailableVideoProfile` exposes profile ID, mode, `status=UNKNOWN|UNSUPPORTED` and reason, with no executable rules/defaults. These profiles are readable but cannot be selected for an executable plan. Empty lists, missing catalog entries and unknown bounds never mean unlimited support.

### Catalog validation

Java validates the code catalog on startup and refuses an invalid registration:

- Unique model keys, profile IDs, preset IDs, and ratio/resolution pairs; valid revision and media/profile types. Every model has a structurally valid provider binding; a verified executable registration requires a registered provider/model/adapter combination supporting its media type, profile, `request_type` and `schema_version`. Unknown or unsupported bindings cannot establish executable support.
- Nonempty domains and unique options; ordered bounds, valid steps and safe numerical products.
- Video output option references a declared preset. Each declared supported preset has an output option; options cannot advertise forbidden size/duration combinations.
- Reference media limits define the profile's supported input combination. Each `media_type` entry is unique. A profile with no references must forbid them; required image/video/audio counts must coexist within total limits. `AUDIO` requires `dimensions=null`, and `IMAGE` requires `max_duration_milliseconds=null`; dimension/duration fields and MIME types must fit the declared input media type.
- Every supplied default fits its profile. If multiple defaults are present, their combination must be supported. Required user inputs need not have defaults. Prompt and references have no catalog default.
- Preset dimensions and ratio agree. Image custom sizes are checked by bounds/steps/area/ratio; video custom sizes are not supported by this v1 contract.
- Every verified profile requires at least one `OFFICIAL_DOCUMENTATION` or `CONTRACT_VERIFICATION` evidence entry. `PRODUCT_POLICY` alone cannot establish provider support; it can only narrow a verified range.
- A registered normalized path must have an implemented adapter/result-verification path for exactly one output file with auxiliary outputs explicitly disabled. A shape-compatible provider parameter is not sufficient evidence.
- Duplicate boolean choices, undeclared formats, duplicate or impossible media limits and unknown executable profiles are rejected. Validate the positive platform submission limit separately from model registrations.

## Normalization and requirement matching

Normalization is deterministic and runs only for drafts. For creation and updates:

1. Decode the exact variant and check primitive invariants, owner, Session, focus and revisions.
2. Apply the typed requirement and candidate patches atomically. Reject a malformed or unsupported explicitly selected candidate; well-formed unsupported requirements are allowed.
3. Remove a previous `MODEL_DEFAULT` candidate if a new requirement contradicts it. Never overwrite an incompatible explicit candidate silently; retain it with a requirement blocker.
4. If a candidate component is missing and has a user requirement, copy that requirement only if the selected profile can support it. Otherwise leave it missing and report the requirement blocker. Without a selected model, well-formed requirement values can populate the candidate but remain unvalidated for submission.
5. Apply a catalog default only to a missing component without a requirement. Never use it to resolve an unsupported requested duration, size, format or reference, or to increase requested file count.
6. When a model is selected, resolve the provider binding from its exact capability revision, validate its adapter/result-verification support and the complete combination, then return the full snapshot. A missing component, reference metadata, compatible default or supported binding may leave a successfully saved draft non-submittable. Invalid inferred default combinations are blockers, not permission to change explicit choices.

Scalar requirements compare exact values. Ratio requirements compare reduced fractions; pixel requirements compare concrete dimensions, including dimensions resolved from a preset. A partial ratio-size requirement constrains only its nonnull components. A resolution requirement matches the selected preset's resolution ID; an arbitrary custom pixel size does not imply that label. Format and audio-track requirements are explicit. Requested file count is checked against platform submission policy rather than copied into a candidate. Description does not replace these checks.

`parameter_origins` and newly `applied_defaults` are computed by Java. The agent must explain default values using the returned parameters, not invent them. On freeze, Java resolves all required candidates into `ImageExecutionParameters` / `VideoExecutionParameters`, copies the exact resolved provider binding into the normalized request and persists it. Frozen reuse never invokes normalization, applies current defaults or substitutes a newer binding for the same model ID.

## Tool call inputs

The model sees only the named `arguments` types below. Python injects owner/Session identity and the original call Item ID; the model cannot supply those identities or parameter origins.

| Tool | Complete argument fields | Return result |
| --- | --- | --- |
| `get_model_catalog` | nullable `media_type` | `ModelCatalogSnapshot` |
| `read_generation_plan` | `query: PlanQuery` | `GenerationPlanReadResult` |
| `create_generation_plan` | `target`, matching `intent`, matching `parameters`, `expected_focus_revision` | `PlanMutationReceipt` |
| `update_generation_plan` | `plan_id`, `expected_plan_revision`, `expected_focus_revision`, matching `patch` | `PlanMutationReceipt` |
| `change_generation_target` | `plan_id`, `expected_plan_revision`, `expected_focus_revision`, `target` | `PlanMutationReceipt` |
| `clone_generation_plan` | `source_plan_id`, `expected_source_plan_revision`, `expected_focus_revision` | `PlanMutationReceipt` |
| `focus_generation_plan` | `plan_id`, `expected_focus_revision` | `PlanFocusReceipt` |
| `submit_generation_plan` | `plan_id`, `expected_plan_revision`, `expected_focus_revision` | `PlanTaskSubmissionReceipt` |

All tools can return `PlanToolRejection` for a proven business rejection. Existing `get_task` remains the Task observation tool.

`PlanQuery` is `FocusedPlanQuery(type=focused)`, `PlanByIdQuery(type=by_id, plan_id)` or `SessionPlansQuery(type=list, cursor?, limit)`. Limit is 1..50. Cursor contains `before_created_at` and `before_id`; paging uses descending immutable creation time/ID. An unfocused query returns zero plans and the focus snapshot. A by-ID miss returns a scoped rejection. Reads return plan and validation snapshots under one consistent observation.

`GenerationTarget` is `ImageTarget(media_type=IMAGE, model_choice?)` or `VideoTarget(media_type=VIDEO, model_choice?)`. Null means no model selected; it is allowed for new drafts. A known, enabled, verified target choice must match the observed capability revision. A new media type and its parameters/requirements must agree. Clearing a draft's model is an explicit target operation and blocks submission.

### Typed patches

`ImagePlanPatch` / `VideoPlanPatch` includes `description`, matching `requirements` patch and matching `parameters` patch. Every patch field is one of named `KEEP`, `SET(value)` or `CLEAR` variants. Description allows only KEEP or nonblank SET.

```json
{
  "op": "SET",
  "value": 2
}
```

```json
{"op": "KEEP"}
```

```json
{"op": "CLEAR"}
```

SET values cannot be null. Requirements CLEAR removes that requirement; candidate CLEAR removes the current choice. A missing unconstrained candidate may immediately receive an explicit catalog default. Clearing a required candidate does not remove its requirement and does not authorize a conflicting default. Candidate references CLEAR becomes the empty/missing selection `[]`; requirement references CLEAR becomes null, while requirement SET `[]` requires no references. A candidate SET `[]` remains eligible for supported requirement materialization.

`requested_file_count` uses `RequestedFileCountChange` in the requirements patch only. SET requires a positive int32; CLEAR restores null and its effective count of one. Shared `SetReferences` / `ReferencesChange` types apply to both image and video reference patches. Size and reference patches replace their entire values. There are no arbitrary field paths or JSON Patch dictionaries. Known model IDs, profiles, media types, revisions, state, parameter origins and frozen snapshots are not patch fields.

### Target changes and clone

Same-media change requires DRAFT. Java resolves the target and migrates the candidate in one transaction, preserving the whole intent. A no-op choice does not advance plan revision. A genuinely changed configuration advances it once.

Migration proceeds in a stable order:

1. Preserve a prompt only when it meets target rules. Preserve a custom pixel size only if supported; for ratio sizes, retain an allowed aspect ratio first and a compatible resolution second, clearing unsupported components.
2. Preserve the reference list only when all actual media types are supported, maintaining its order. Clear the whole list rather than silently choose a subset when media, cardinality or aggregate limits fail. Missing trusted metadata causes a blocker rather than an invented incompatibility.
3. For video, retain supported duration for the chosen size and reference budget. Clear duration if that combination fails. Retain supported format and audio-track choice.
4. Preserve all requirements, normalize only eligible missing candidates, and report cleared fields and remaining issues. A mandatory missing reference is a blocker, not permission to invent input semantics. Requested file count remains unchanged and is validated separately against platform policy.

When only a partial size is available, a retained duration must fit at least one still-possible option; final validation checks the actual selected option. Normalization never searches for a replacement for a conflicting explicit choice.

Cross-media change works from DRAFT or FROZEN and creates a new DRAFT after validating source ownership, Session membership and the requested plan revision. It copies the description as context, a requested aspect ratio if present, and `requested_file_count` including null. It does not copy literal prompt requirements, prompt candidates, profile/mode, resolution or pixel sizing, formats, references, duration or audio-track choices. Their meanings differ between media types. The source retains all original requirements and parameters. The result lists `non_migrated_requirement_fields`; the agent must surface them and use explicit target-typed updates to adopt any of them. It cannot automatically assign frame semantics to a reference.

Clone works from either state, validates source ownership and Session membership, locks the current source record and requires its revision to equal `expected_source_plan_revision`. A mismatch returns `PLAN_REVISION_CONFLICT`; the tool does not read historical versions. It then copies the current intent/configuration into a new DRAFT, clears frozen metadata and focuses the clone. It never changes the source. The pinned selection and values are copied without new defaults or a refreshed binding; current support for that exact revision and its binding is reevaluated for readiness. Only an explicit new target choice can select a different binding. A clone does not itself invoke generation. Neither clone nor cross-media creation persists a source-plan relationship on the new plan. The operation receipt retains `source_plan_id` and the new plan's complete creation snapshot as operation evidence.

### Returned structures

`GenerationPlanSnapshot` contains `plan` and `PlanValidation(can_submit, issues, validated_catalog_revision)`. Validation is a current observation, not part of the immutable stored plan. `can_submit` is true only when issues is empty, the selected capability remains allowed, and all admission prerequisites are satisfied. A frozen plan can be valid historically but unavailable for a new submission today.

`ValidationIssue` has a closed code, closed field, optional reference index, bounded message and nullable model selection. It does not contain generic arbitrary values. The complete typed request, current parameters and catalog give the values/options; reference indexes correlate reference errors. Issue codes are declared in the schema, including unsupported requirements, missing metadata, combination conflicts, missing adapter and missing result verifier.

`PlanMutationReceipt` returns action, nullable source plan ID, full snapshot, focus snapshot, cleared candidate field list, non-migrated requirement field list and `AppliedDefault(field, source_model)` array. Retained defaults are not newly applied defaults.

`PlanFocusReceipt` returns focus. `PlanTaskSubmissionReceipt` returns `plan_id`, `frozen_plan_revision`, `accepted=true`, the effective accepted `requested_file_count`, a nonempty `tasks` list and observed focus. Each `SubmittedTask(index, task_id, task_operation_id)` has a zero-based contiguous index. List length must equal the count; Task IDs and child operation IDs must be unique, and no child operation ID may reuse the parent operation ID. Even one Task uses a list of length one. The parent `operation_id` appears only in the outer `JavaPlanToolOperationReceipt`; Java assigns an independent UUID to every child operation, including the one-file case, and persists the complete mapping atomically. Acceptance does not imply provider acceptance or successful generation.

`PlanToolRejection` contains closed error code, message, nullable current plan/focus revisions, typed issues and `business_execution=NOT_PERFORMED`. This means no plan/focus/Task business mutation or generation was performed; storing a rejection receipt is allowed. It cannot describe an uncertain HTTP/database/provider outcome. Local batch preflight can return the same rejection variant with absent revisions and no Java invocation.

## Java tool transport and receipts

The adapter builds `JavaPlanToolInvocation(type=generation_plan_tool_invocation, operation_id, session_id, tool_name, arguments)`. `operation_id` equals the original TOOL_CALL Item UUID for every plan tool, including reads. The provider's `call_id` remains separate and must be preserved for call/result pairing.

Proposed internal routes:

- POST `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-tools/{tool_name}` accepts the named invocation and returns a typed `JavaPlanToolOperationReceipt`.
- GET `/internal/v2/users/{user_id}/sessions/{session_id}/generation-plan-tool-operations/{operation_id}` reads only the original operation receipt.

Path Session/tool must match the body and trusted runtime binding. Owner remains selected by the current trusted internal user path; this proposal does not make that path authentication. All lookup and authorization remain owner/Session scoped.

`JavaPlanToolOperationReceipt` contains schema version, operation ID, Session ID, tool name, SHA-256 request hash and one closed `GenerationPlanToolResult` variant. Hash input is the original owner/Session/tool/typed arguments, not latest plan content. User-facing Item content keeps the original model arguments; injected operation metadata belongs to the transport/receipt.

Reuse existing `http_request` idempotency storage and transaction infrastructure with a named envelope, distinct operation namespace and typed original-receipt lookup. Do not introduce a second receipt table merely for plans. Store successful results and validly decoded business rejections. A malformed request can fail at ingress without a receipt. Technical failures retain UNKNOWN unless nonexecution is proven.

Receipt lookup and replay precede current version/capability checks. The same key and full request returns the original response, including its old snapshot/focus and any rejection. Reuse with different owner-scoped Session/tool/arguments conflicts. Correcting a rejected call uses a new call/operation identity. Replaying an old receipt must not overwrite current focus/context as if it were a fresh observation; fetch a new snapshot when needed.

For an uncertain operation, query its original receipt or resend exactly the same named invocation/identity through the idempotent endpoint. A missing receipt can race an uncommitted request; it is not proof that provider work never occurred. Never create a new operation to recover an uncertain submission. Reads also retain their original observation so runtime recovery does not substitute a newer result for the interrupted call.

## Java validation and transaction rules

| Stage | Required checks | Outcome |
| --- | --- | --- |
| Decode | Closed variant, required fields, no extras, UUIDs, nonblank text, integer bounds, explicit false/null/list semantics | Reject malformed input before business work |
| Operation identity | Original receipt scope and complete argument hash | Replay original result or identity conflict |
| Ownership | User/Session exists; every plan/source and asset belongs to the owner and correct Session scope | Scoped rejection without disclosing another owner |
| Concurrency | Expected business plan and focus revisions; focused source matches explicit ID | Proven typed rejection on stale state |
| State | Only DRAFT may edit/change same-media model; FROZEN permits reads/clone/cross-media creation/repeated submit | PLAN_FROZEN for forbidden mutation |
| Target | Catalog key, enabled model, matching media, verified profile, exact capability revision and its provider binding | No guessed model alias/default/fallback or routing substitution |
| Candidate | Size shape, prompt limit, ordered references, format, video duration/audio track | Invalid explicit assignments reject atomically |
| Requirements | Exact/partial matching rules, profile support and effective requested count versus platform policy | Preserve requirements and expose blockers |
| References | Ownership, current availability, trusted signature/metadata, media/count/MIME/byte/dimension/duration limits | Unknown facts block; they do not establish success |
| Combination | Legal size-duration output option and scenario-specific reference rules | Block unsupported combination |
| Execution readiness | Pinned binding matches provider/model/adapter, media/profile/`request_type`/`schema_version`; implemented normalized adapter/result verifier and current availability | No submission through an unsupported path; use `EXECUTION_ADAPTER_UNAVAILABLE` or `RESULT_VERIFIER_UNAVAILABLE` on `PROVIDER_BINDING` where applicable |
| Submission | Complete effective values, frozen request/count consistency, receipt membership and unique contiguous child identities; reauthorize assets | Atomic freeze/all Tasks/all Outbox envelopes/parent receipt/notifications |

Java must acquire relevant owner/Session/focus/plan locks in one consistent order. Preserve existing short transaction and bounded whole-transaction retry patterns. Media inspection, byte transfer, provider calls and broker publication stay outside database retry callbacks. Preparing facts outside a transaction must bind them to an immutable asset checksum; the acceptance transaction rechecks owner/existence/checksum and the observed catalog revision, including submission policy. It cannot make external provider availability atomic with the database.

Plan revision advances once per actual configuration/state change. No-op updates and rejections leave it unchanged. Source clone/creation does not update the source. First freeze advances revision once and fixes timestamps/request; later submissions from FROZEN do not change plan revision or timestamps. A concurrent first submission with an older draft revision is rejected after the winning freeze, unless it is replaying the same operation. A caller can subsequently submit the observed frozen revision under a new operation to request another generation.

### First and subsequent submissions

For a new operation, check scope/focus/CAS and admission, then:

1. DRAFT: resolve the complete single-file typed request, frozen expectations, parameter origins and provider binding under its exact selected capability revision; validate and copy the binding into the frozen request, determine the effective requested file count, freeze the plan once and increment revision.
2. FROZEN: copy its original normalized request and frozen effective file count without applying defaults, migrating values or recompiling against a new capability revision. Reuse the original frozen provider binding without reading a replacement route from the current definition of the same model ID. The pinned revision/profile/adapter must remain supported, and current submission policy must allow the frozen count; otherwise block and offer clone/change through conversation.
3. Reauthorize referenced Assets. For count N, assign N new Task IDs and N independent child `task_operation_id` UUIDs, including when N is one. Store N Tasks with owner, Session, plan ID, child execution identity and an immutable copy of the same single-file normalized request.
4. Save N generation Outbox envelopes, each bound to its Task and child operation identity, and one complete parent operation receipt containing all `SubmittedTask` entries; emit plan/focus/Task frontend invalidations as applicable; commit all together.

Admission is all or nothing: rollback leaves a draft unfrozen and creates no Tasks/Outbox envelopes. An uncertain response after commit is reconciled through the original parent receipt, never by allocating fresh children. Child Tasks execute independently after acceptance; one child's provider/import failure does not roll back or replenish another child, unfreeze the plan, mutate its request or automatically start another generation. Replaying the parent returns its exact original ordered child identities.

### Agent batch ordering

Current tools execute concurrently. Expected revision alone does not protect an update-and-submit intent: submit may win and freeze the pre-update version.

Before invoking any Java tool in a model response, Python examines the whole batch. Reject all members of a dependent group before invocation when it includes multiple same-plan mutations, mutation plus submission, multiple draft submissions, or a focus-changing operation together with any focus-dependent mutation/submission. Return `DEPENDENT_TOOL_BATCH` with proven nonexecution. Run independent groups only when their semantics permit it.

The model must first mutate, observe the new snapshot/revision, then submit in a later model response. Read-only observations can run concurrently; they represent independently captured observations. Multiple independently intended submissions of the already focused FROZEN revision may run concurrently; each creates its own frozen requested count of Tasks. They are not merged into an implicit file batch and must not substitute for an explicit multi-file requirement or evade submission policy. Stopping a Run does not undo a Java-accepted Task.

After conversation compaction and before each model request, the adapter supplies a fresh authoritative focus, focused plan snapshot and relevant catalog profile after compacting history. If authoritative reads fail, dependent generation is blocked; do not reconstruct state from a summary. Checkpointed IDs/snapshots are references and observations, not Java state authority.

Context hydration is an internal read-only observation, distinct from an LLM tool invocation. It uses scoped read services/endpoints without manufacturing a TOOL_CALL Item or a model operation receipt. The eight explicit model tools retain their original observation/operation receipts as specified above.

## Frozen requests and result expectations

`ImageGenerationRequest` / `VideoGenerationRequest` carries a unique `request_type` discriminator, `schema_version=1`, model selection, required `provider_binding`, complete execution parameters and matching result expectations. These are the closed `NormalizedGenerationRequest` variants. The discriminator must be decoded explicitly; a malformed normalized variant must never fall through to legacy provider-request parsing. Java derives the binding from model selection at the exact capability revision and checks media/profile/discriminator/version consistency before first acceptance; the LLM never supplies it.

The connector selects a verified registered implementation using the binding together with `request_type` and `schema_version`. These two fields identify the wire contract, not a provider route on their own. It checks provider/adapter/model/profile compatibility and explicitly rejects an unknown or unsupported route; it cannot switch provider or model as fallback. The frozen binding is part of every child Task's immutable normalized request, and submission file count never becomes a provider execution parameter.

Execution parameters have no null required values. Image uses resolved preset or validated custom pixels. Video uses a resolved preset and explicit duration/audio-track values. Generation mode is the selected profile's output media type. Requested file count does not appear in execution parameters. Asset references remain stable IDs in Java's plan and Task snapshot.

`ImageResultExpectations` fixes `file_count=1`, size and encoding. `VideoResultExpectations` additionally fixes duration/tolerance and expected audio-track presence. Java verifies expectations agree with parameters and capability evidence before acceptance. No auxiliary output is permitted on this normalized path: the provider adapter must explicitly disable auxiliary outputs and support exactly one file per Task. If the provider exposes a count parameter, the adapter must explicitly fix it to one; it must not rely on an unknown default or pass the submission file count downstream. A path that cannot establish this contract is unavailable rather than silently relaxed.

Request validation does not prove output fulfillment. Before business success, the owning result/import boundary must verify exactly one file, detected encoding, concrete dimensions, video duration tolerance and audio-track requirement. Zero or multiple returned files are confirmed result failures; the connector and importer must not silently discard extra files, widen accepted count or submit another paid call. Existing verified-Asset preservation under failed-Task rules remains separate from successful single-file fulfillment. An expected audio false value means no audio track, not merely no requirement to check audio.

The connector needs an authorized Asset resolver/delivery boundary to turn stable IDs into provider-readable media. Each new Task can receive fresh transport locators while reusing the frozen plan. The connector fixes the selected account and persists the exact resolved provider payload and adapter identity before possible dispatch. Account/credential handling remains connector-owned and does not enter the plan binding. Replay or recovery must not change the frozen binding, selected route or saved provider payload; an uncertain operation is never resubmitted because a URL expired. Signed locators and credentials do not become plan data or model-visible arguments.

Provider-specific flags that are outside this normalized v1 contract remain connector-owned. The connector records its adapter identity and complete submitted payload for recovery. It must not infer, discard or rewrite a declared normalized requirement silently. No automatic model selection, fallback or implicit fan-out is introduced. Within one submission, multiple Tasks arise only from the explicit requested file count admitted by Java.

## Persistence impact

This is a logical inventory, not executable DDL or authorization to reset data. Existing twelve-table Java storage remains owned by Java.

| Table | Proposed effect |
| --- | --- |
| `generation_plan` | New: identity/owner/Session/revision/state, typed configuration JSON, frozen single-file request, frozen intent file count and timestamps. Composite owner/Session constraints and revision CAS; no submitted Task column. |
| `session` | Add nullable focused plan ID and independent nonnegative focus revision. Same owner/Session membership must be enforced. |
| `task` | Add nullable `plan_id` for existing/direct historical Tasks; every new plan-tool child Task requires it and carries the frozen single-file typed payload and independent child operation identity. Enforce same owner/Session relation. No Run/Turn/Item FK. |
| `http_request` | Reuse existing request identity/hash/result storage; add typed operation envelope and scoped lookup at the application boundary; atomically retain the full parent-to-child receipt mapping. No second receipt table. |
| `session_event` | Retain storage; add typed PLAN_UPDATED and PLAN_FOCUS_CHANGED notification variants. |
| `user`, `agent_run`, `agent_turn`, `asset`, `item`, `run_event`, `outbox_event`, `inbox_event` | Retain tables. Extend named serialized contracts where required; no schema reset or new business scheduling state. |

Frozen plan Assets must remain protected from unused-upload cleanup while referenced by a retained plan. Asset removal that cannot preserve references requires an explicit separate lifecycle policy; v1 must not silently delete referenced inputs. Generated and uploaded Assets still retain their current ownership/storage responsibilities.

## Integration changes required before activation

- Java: named plan/catalog/domain DTOs and validators, including required binding resolution at the exact capability revision and frozen request persistence; feature repositories; business revisions/focus; reused receipt envelope and lookup; atomic multi-Task entry; scoped frontend query/notifications; safe retention of referenced Assets.
- Python agent: matching named tool inputs/results; original operation identity for all plan tools; typed receipt recovery; batch dependency preflight; authoritative context hydration; canonical Item serializers/decoders and fixtures.
- Connector: explicit normalized variant decoding, binding/discriminator/version-based registered adapter selection and compatibility checks, Asset locator resolution, provider mapping, strict single-file and disabled-auxiliary-output semantics and output expectation verification. Unknown bindings block; existing saved routes and provider payloads remain immutable during recovery. Existing immutable execution/recovery identity remains unchanged.
- Extend Java `ConversationContent`, `EventProtocol`, `ContentValidator` and Python protocol/model-observation unions together. Plan-tool results, including historical replay, must validate against membership in their original parent operation receipt, including each child Task/operation ID, not latest mutable plan state. Historical legacy tool results retain their original decoders and receipt validation.
- Expose `submit_generation_plan` as the agent's generation entry and retain `get_task`. Do not also expose the old opaque `submit_generation(kind, request)` as an LLM bypass. Preserve historical decoding where required.
- Coordinate transport activation with the existing generation v2 envelope. Its `request` becomes an explicitly discriminated normalized/legacy union; new receivers must understand both before producers send the normalized variants. A legacy-only connector rejects new fields. No current-topic activation is performed by this design.

Current source boundaries: [Java opaque request](../../../src/main/java/com/lux/backend/task/ConnectorRequest.java), [Task acceptance](../../../src/main/java/com/lux/backend/task/TaskService.java), [idempotency storage](../../../src/main/java/com/lux/backend/common/HttpIdempotency.java), [Asset facts](../../../src/main/java/com/lux/backend/asset/AssetRecord.java), [agent parallel tools](../../../../lux-python-agent/src/lux_agent/runtime.py), [provider request shapes](../../../../lux-python-model-connector/src/lux_model_connector/byteplus.py).

The current Asset record lacks trusted dimensions/duration, the connector accepts provider-readable strings rather than Java Asset IDs, and current result preservation does not establish strict single-file/size/duration fulfillment. Current `TaskService` receipt recovery assumes one Task, and `ContentValidator` assumes the parent call Item ID equals the Task operation ID. Activation requires recovering the full original parent receipt and validating child membership instead of that equality. The active opaque request path also lacks this explicit provider-binding contract and its pinned adapter compatibility checks. These are implementation dependencies for this contract, not checks already provided by the plan schema; this design implements none of them.

## Acceptance scenarios

Implementation validation should protect observable behavior:

1. A partial draft preserves user intent across new model calls/compaction, and missing fields/default sources are returned accurately.
2. Model change preserves requirements, clears incompatible candidates atomically and blocks conflicting defaults; compatible values keep their parameter origin.
3. A size/duration/reference combination fails even when each value is independently supported. Unknown capability and missing reference facts never pass as unrestricted.
4. Cross-media creation retains its source, reports non-migrated requirements, copies requested file count without inventing frame semantics and changes focus atomically.
5. First acceptance freezes once and creates N Tasks/N Outbox envelopes/one parent receipt atomically. A rollback freezes nothing and creates no children; a repeated original operation returns the original ordered identities before CAS checks.
6. Reusing one frozen plan under new parent operation IDs creates its frozen count of distinct Tasks with identical single-file normalized requests and leaves plan revision unchanged. Updating it fails; clone yields an editable draft, including for count changes.
7. Focus and plan CAS reject stale work; dependent same-response update/submit is rejected before invocation, rather than freezing old parameters.
8. Original receipt lookup recovers writes and read observations without replacing them with current state. Cross-owner/Session lookup does not disclose data.
9. Trusted metadata enforces actual asset media types, counts, dimensions and duration budgets; resolver URLs/credentials never enter plan or LLM arguments.
10. Null requested file count yields one; an explicit multi-file requirement creates exactly N independent children within platform policy. Above-policy counts remain saved blockers. Each child requires exactly one file with auxiliary outputs disabled; missing or extra files fail without cropping or automatic paid replenishment.
11. Java/Python canonical Item/tool and normalized/legacy connector fixtures agree, including false, null, arrays, revisions and unknown-property rejection.
12. Referenced frozen-plan inputs survive normal unused-upload cleanup. Provider uncertainty reconciles the original operation, never a new submission.
13. First acceptance fixes the exact capability revision's provider binding. Changing a catalog binding advances revisions; frozen reuse and recovery retain the original route/payload. An unavailable pinned adapter/result verifier blocks through existing issue codes rather than rerouting. Clone retains its pinned selection until an explicit target change.

The supplied JSON Schema/examples validate structural contracts only. Local runtime and database tests exercise synthetic registrations; real-provider execution, deployed activation and provider result-verification acceptance remain separate evidence requirements.
