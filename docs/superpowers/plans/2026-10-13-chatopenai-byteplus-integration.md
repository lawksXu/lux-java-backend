# Lux ChatOpenAI and BytePlus Implementation Plan

The user's final boundary decision governs this implementation: Agent directly uses `ChatOpenAI` through `ModelExecutor`, Agent retains model receipts/recovery, and Connector owns image/video submission, polling and retained results. This supersedes the proposed Connector model HTTP interface in the earlier design.

## Global constraints

- No live provider calls, credentials in artifacts, deployment, branch, commit, instruction-file changes, or existing database reset.
- Use existing Java 17 and Python 3.14 environments. New `langchain-openai` is required by the selected SDK; preserve compatible existing pinned dependencies.
- Known business data uses explicit named types. Provider originals may be opaque only inside a named archive.
- Keep Task/Session ownership and native Agent checkpoint/receipt durability unchanged. Never repeat a marked paid submission after uncertain acceptance.
- Test meaningful behavior first and observe failure before implementation. Report local/component proof separately from live transport/provider proof.

## Shared generation registrations

| Lux model ID | Profile | Capability revision | Provider ID | Provider model | Adapter ID |
| --- | --- | --- | --- | --- | --- |
| `seedream-4.5` | `standard` | 1 | `byteplus` | `seedream-4-5-251128` | `byteplus-seedream-v1` |
| `seedream-5.0-lite` | `standard` | 1 | `byteplus` | `seedream-5-0-260128` | `byteplus-seedream-v1` |
| `seedream-5.0-pro` | `standard` | 1 | `byteplus` | `dola-seedream-5-0-pro-260628` | `byteplus-seedream-v1` |
| `seedance-2.5` | `standard` | 1 | `byteplus` | `dreamina-seedance-2-5-260628` | `byteplus-seedance-v1` |

Each normalized request is schema version 1 and expects exactly one primary output. Account reference defaults to existing `byteplus-primary`. No fallback or cross-version aliasing.

## Task 1 Read-only recovery

Files: Agent `backend.py`, `runtime.py`, `tests/test_backend.py`, `tests/test_runtime_v2.py`.

- [x] Write timeout/503 tests asserting one settled original `get_task` ToolError and later Turn/stop/input progression.
- [x] Test Store/checkpoint reopen with an original `IN_FLIGHT/UNKNOWN` read: settle as an interrupted, unobserved read without querying a newer snapshot under that call.
- [x] Implement the localized read-only classification/reconciliation; preserve mutating/model UNKNOWN semantics and original call IDs.
- [x] Run targeted tests and the existing Agent suite. Update the existing diagnostic so a successful fix is explicitly reported rather than crashing on a now-finished Turn.

## Task 2 ChatOpenAI ModelExecutor and recovery

Files: new Agent `openai_model.py` and adapter tests; Agent `store.py`, `main.py`, lifecycle tests, `pyproject.toml`, README.

- [x] Test typed text/tool/reasoning output conversion, complete tool-call IDs/arguments, live text fragments and original message retention with deterministic HTTP/SDK fixtures.
- [x] Use `ChatOpenAI(model="gpt-6-luna", use_responses_api=True, max_retries=0)` and compatible pinned SDK. Keep full Agent-owned context; do not replace Session execution with a provider conversation.
- [x] Persist original provider response identity as soon as the SDK exposes it, before forwarding dependent observations. Scope its receipt to the existing execution ID and account/request binding using explicit typed storage.
- [x] Reconcile a saved original response through provider retrieval; a missing response identity remains UNKNOWN. Never recover by a second model invocation. Confirmed original failure is persisted distinctly.
- [x] Wire executor and reconciler together in ASGI lifespan. API key absence/default-disabled execution must cause no provider request.
- [x] Run actual ChatOpenAI against offline MockTransport/SSE fixtures, including disconnect, partial tool arguments, retrieved completion and restart evidence. Document exact enable/configuration fields and limits.

## Task 3 Connector normalized BytePlus adapters

Files: new Connector adapters/catalog/Asset client and tests; Connector `generation_plan.py`, `execution.py`, `store.py`, `main.py`, README.

- [x] Test the four exact registrations, version-specific image formats, fixed pixel requests, one-file semantics and immutable prepared payload.
- [x] Reuse typed BytePlusClient and retained-result pipeline. Sequential disabled is 4.5/Lite only; layer decomposition disabled is Pro only.
- [x] Add a typed asynchronous accepted result carrying Seedance's original external ID; persist that ID using existing storage before polling. Pass saved original receipt/ID into reconciliation, never keep it solely in adapter memory.
- [x] Implement an owner-scoped Java AssetLocator using existing internal Asset APIs. Inputs must become supported provider media, not unresolved Java IDs or unreachable loopback URLs.
- [x] Inspect retained output bytes for format/dimensions and video duration/audio before success; retain Java's independent business verification.
- [x] Assemble populated registry/locator in lifecycle with real execution still opt-in. Test uncertain acceptance, original lookup, preservation and adapter/account mismatch with no provider calls.

## Task 4 Java model catalog and execution registrations

Files: new generation provider catalog/configuration and focused tests; `ModelCatalog.java`, `GenerationExecutionSupport.java`, application configuration and README as needed.

- [x] Test the four entries against the shared bindings, format limits, defaults, preset lookup and submission availability gate.
- [x] Start with finite verified image/video presets; preserve provider-specific differences. Logical ratio and documented encoded dimensions must remain distinct for rounded Seedance outputs.
- [x] Expose exact route availability through explicit opt-in configuration; the catalog does not assert that a provider account or deployed remote process has been verified.
- [x] Run Java focused generation checks, then offline `verify` with the existing Mockito agent if required by this environment.

## Task 5 Integration review and documentation

- [x] Review each implementation for protocol fidelity, secrets, ownership, UNKNOWN/retry semantics and unnecessary abstractions.
- [x] Run complete offline Python suites, Ruff/pip checks, Java offline verification and targeted cross-project binding/serialization checks.
- [x] Replace the earlier proposed design with the user's direct ChatOpenAI boundary and update readiness evidence. No obsolete model HTTP API or Connector chat receipt table is introduced.
- [x] Retain explicit unverified gates: real credentials/account permissions, actual provider output, Docker/MySQL/Kafka/S3 process acceptance, portrait-reference workflow and unresolved provider acceptance without an original identity.

## Completion evidence

Java offline verify:232 passed and packaged. Agent:115 passed. Connector:351 passed/46 external-service skips. Ruff, format and dependency checks passed. Original read recovery and four actual Java-to-Connector contract translations passed. Independent final review reported no remaining demonstrated findings. No live provider calls, production deployment or full MySQL/Kafka/S3 process acceptance occurred.

Review corrections: confirmed OpenAI create rejection cannot block as UNKNOWN; ffprobe counts decoded frames and rejects corrupt/sample-free outputs; per-reference caps3MiB/4MiB/1.5MiB keep maximum count below inline transport budgets. Agent image/frame observation, provider portrait workflow, Java early Seedance ratio rejection and operational attachment of lost original IDs remain explicitly outside the completed units.
