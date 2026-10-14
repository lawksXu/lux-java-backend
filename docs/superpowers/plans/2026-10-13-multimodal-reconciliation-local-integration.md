# Lux Multimodal, Reconciliation and Local Integration Plan

The user authorized completing the remaining three boundaries and local MySQL/Kafka/S3 integration. The previously approved module ownership and provider design remain binding. Real OpenAI/BytePlus invocation waits for keys and later acceptance scope.

## Global constraints

- No live model/provider calls, cloud writes, production deployment, shared-schema reset, instruction-file changes, branches or commits.
- Java owns Asset authorization/business admission; Agent owns observation decisions, model context/receipts/recovery; Connector owns media generation/original receipt/preservation.
- Define known business structures with named types. Preserve existing field names, original identities, immutable receipts and readable persisted data.
- Save original provider evidence before observation/polling. Unknown acceptance never authorizes another POST. Operator attachment resumes original lookup/preservation only.
- New reconciliation HTTP routes default disabled. Derive operator identity and allowed account from trusted server configuration and constant-time Bearer authentication; never trust a body operator or owner selector as authentication.
- Additive owned-state migrations only, no reset. Keep credentials out of artifacts and logs. Use task-owned isolated local infrastructure and clean up only owned resources.
- Use targeted failing behavioral tests before implementation, then independent review and complete relevant local checks.

## Task 1 Agent multimodal observation

Ownership: Agent execution.py, runtime.py, openai_model.py, new media observation/client modules, dedicated tests. Root owns Agent main.py, store.py and final README integration while the reconciliation task runs.

- Add explicit image/video-frame observation descriptors scoped to original user/Session/source Item or immutable Task snapshot, Asset ID, checksum, media metadata and frame timestamp. Keep Base64/temporary URLs outside durable messages/checkpoints.
- Resolve uploaded input Assets and fresh TaskQuerySnapshot result Assets through Java owner-scoped metadata/content endpoints. Never query Java's bucket directly or rewrite historical ToolResults.
- Support PNG/JPEG images and bounded MP4/MOV video frame sampling for Luna image input. Inspect actual bytes, enforce checksum/MIME/decoded-pixel/download limits, no redirect or shell/protocol escape. Initial policy: source images <=10 MiB, videos <=100 MiB and <=120 seconds; at most8 sampled frames per video, <=8 media observations per invocation, frame/image presentation <=1024 pixels on longest edge and <=1 MiB JPEG each. Explicitly reject unsupported audio observation.
- Freeze compact observation descriptors into the original ModelRequest before provider dispatch. Resolve verified images/frames to actual ChatOpenAI input_image blocks only for invocation. Preserve full original history, call identities and paired tool outputs.
- Backward compatibility: adding empty observation fields must not change old persisted ModelRequest serialization/request hashes. Recovery of accepted work queries original response without re-fetching or resubmitting media.
- Test real ChatOpenAI HTTP payload with image blocks and timestamped frames, fresh Task result association, owner isolation/integrity/limits, unavailable media without a model POST, immutable history, compact native checkpoint/store reopen and original recovery.
- Provide media lifecycle wiring instructions to root; no new provider calls or unrelated deps. FFmpeg tools already exist locally; enabled media observation must check tools explicitly.

Report: /private/tmp/lux-multimodal-report.md.

## Task 2 Audited original receipt attachment

Ownership: Connector reconciliation module/store/schema/main/tests/README; new Agent reconciliation module and Agent store.py only. Root assembles Agent main.py routes/lifecycle and final Agent README. Do not edit Agent runtime/execution/openai_model files owned by Task1.

- Add typed original-evidence views and attachment requests. Require owner/Session/execution or Task/original operation binding, saved request hash and exact account/provider binding plus evidence reference/reason. Operator identity comes only from authenticated server config.
- Agent attaches an original OpenAI response ID to an existing missing-ID UNKNOWN/IN_FLIGHT model execution. Preserve saved model/endpoint/org/project/account/request bindings. Never replace a conflicting ID, overwrite terminal execution or create another invocation. Same attachment is idempotent; audit and ID commit atomically.
- Connector attaches a proven original Seedance task ID or typed original Seedream receipt to a marked unresolved execution. Validate immutable prepared submission, owner/Session/Task/operation/account/model/hash. Retain existing provider terminal/ID evidence unchanged. Resume normal original GET/preservation pipeline, no second provider POST or direct Task success.
- Persist additive owned audit records with trusted operator, original binding/evidence digest and timestamp. Existing databases remain readable. Update provisioning/validation and provide explicit additive upgrade command for existing Connector schemas if required.
- Expose optional default-disabled internal routes protected by configured Bearer token and server-side account scope. Include unresolved read/detail and attachment operations; no unauthenticated introspection, no operator field in body. Never expose credentials/large original request media on views.
- Tests: disabled/unauthorized access, scoped owner/hash/account/model mismatch, immutable/terminal conflicts, same-evidence replay, concurrent attachment, atomic rollback, restart, original query/preservation and zero new provider submissions. Include real MySQL coverage with the isolated test config when ready.
- Document manual original-evidence attestation honestly: an ID supplied by an authenticated operator is not automated provider idempotency or cryptographic proof of the original request. When no original evidence is available, remain unresolved.

Report: /private/tmp/lux-reconciliation-report.md.

## Task 3 Java Seedance reference admission

Ownership: root Java generation feature/tests and coordinated contract fixtures only if required.

- Reuse existing typed catalog/domain/actual Asset metadata. Reject Seedance image ratio below0.4 or above2.5 before Task creation, preserving inclusive boundaries and exact provider/model/profile/revision checks.
- Prefer an additive typed ratio-range capability in ReferenceMediaLimit only if it avoids provider-specific conditionals in generic domain rules. If a contract extension is necessary, preserve omitted-field old JSON readability and update Java/Python explicit types, schema/examples and documentation together.
- Test300x750 and750x300 accepted, neighboring invalid dimensions rejected, no child Tasks/outbox/provider work on invalid submit, metadata unavailability remains explicit and other models retain existing rules.

## Task 4 Local MySQL/Kafka/S3 acceptance

Ownership: infrastructure agent scripts/harness; root coordinates complete component and process runs after feature changes.

- Diagnose Docker Created-state blockage and use a safe working local daemon endpoint; do not repeatedly retry unchanged failure or modify shared services.
- Create isolated task-owned MySQL8.4, Kafka4.0.2 and SeaweedFS4.47 services, schemas/groups/buckets with explicit manifest/private config.
- Run Java integration suites and Connector opt-in MySQL/Kafka/S3 suites against real local services. Record actual business assertions, failures and skips distinctly.
- Add a reproducible real Java/Agent/Connector process acceptance path over MySQL/Kafka/S3, using deterministic OpenAI/BytePlus HTTP substitutes. Cover Session input -> Agent plan tools -> normalized Task -> Connector retained bytes -> Java Asset/Task terminal input -> Agent result observation; lost-ID attachment and restart/no-resubmit; image and video actual bytes; scope/negative/SSE and original identities.
- No fake Java PythonConsumers qualify as complete process proof. Substitute provider/model HTTP only, retaining production clients, workers and stores where feasible.
- Clean up owned resources at end after retaining evidence, preserving shared images/services/data. Secrets/config are private temporary files, not repository artifacts.

Report: /private/tmp/lux-local-infra-report.md; final acceptance evidence in this repository's integration readiness document.

## Progress

- [x] Task1 implemented, reviewed and verified: full Agent 158 passed, including hydration cancellation, original-response recovery and real SDK media/header serialization.
- [x] Task2 implemented, reviewed and verified: full Connector 417 passed with real MySQL/Kafka; pending-write receipt convergence re-reviewed clean.
- [x] Task3 implemented, reviewed and verified: Java 266 passed with real MySQL/Kafka/S3, including inclusive ratio boundaries and no Tasks/Outbox for rejected references.
- [x] Task4 real local component/process acceptance completed and evidence recorded: actual three-process image/video loops, Seedance/OpenAI lost-ID restart/attachment/original GET with one original POST each, scoped bytes/SSE/range/ratio checks and cross-database assertions. No live provider calls. Broader unexecuted fault combinations remain explicitly prepared cases in the readiness matrix.
