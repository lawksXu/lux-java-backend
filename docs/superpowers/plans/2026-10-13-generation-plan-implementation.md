# Generation Plan Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to execute the independent module tasks and review their integration. Track completion below; do not create branches or commits.

**Goal:** Implement the approved reusable generation-plan contract across Java, the agent and the connector without registering production generation models or submitting live provider work.

**Architecture:** Java owns durable plan configuration, focus, capability policy, validation, atomic child Task acceptance and original operation receipts. The agent uses typed Java tools and hydrates authoritative context before model calls. The connector explicitly decodes normalized requests and resolves registered provider adapters, retaining legacy decoding and immutable recovery.

**Tech Stack:** Existing Java 17/Spring Boot/JPA/Flyway/Jackson and Python 3.14/Pydantic/LangGraph/HTTPX dependencies.

## Global Constraints

- Read each repository's AGENTS.md. Do not modify instruction files, create branches/commits, deploy, push, use real accounts or call paid providers.
- The English specification and JSON Schema under docs/fixtures/generation-plan-v1 are the exact wire contract. All 144 named definitions must remain explicit types; raw JSON is allowed only at schema decoding or opaque legacy/provider archival boundaries.
- Preserve null, false, ordered arrays, required nullable fields and rejection of unknown properties. Java does not use var.
- Catalog registrations remain empty in production. Use unregistered test fixtures to verify supported-model behavior. Production submission policy defaults to 32 Tasks per explicit submission, independently of model capabilities.
- Keep Task/Session ownership direct; each child Task produces exactly one file. Do not change legacy Task result-count semantics.
- Add forward Flyway migration under db/session-storage; do not reset databases or modify historical migrations.
- Original tool operation ID is the TOOL_CALL Item ID. Child execution IDs are independently allocated and atomically retained in the original parent receipt. Replay precedes current CAS/capability checks.

## Task 1: Java contract, catalog and pure domain rules

**Files:** Create generation/GenerationPlanContract.java, GenerationPlanDomain.java, ModelCatalog.java and their tests. Add a schema ingress codec only if required for exact Jackson wire decoding. Do not edit persistence, chat or Task classes in this task.

**Interfaces:** The domain component produces typed configuration changes, PlanValidation and NormalizedGenerationRequest for the transaction service. Publish exact method signatures to the controller before adding integration dependencies. Keep all contract types nested in GenerationPlanContract unless a focused existing convention is more suitable.

- [x] Add fixture decoding/round-trip and domain regression coverage.
- [x] Implement explicit records/closed unions for all schema types and strict ingress decoding. Preserve exact field names and subtype discriminators.
- [x] Implement an empty typed catalog with independent submission policy, catalog-registration validation and precise capability lookup.
- [x] Implement normalization, requirement matching, parameter origins/defaults, same-media candidate migration, cross-media creation, clone preservation, blockers and frozen request creation.
- [x] Verify false/null/reference requirements, unsupported explicit assignments versus retained unsupported requirements, size/reference/duration combinations, catalog/default errors and ProviderBinding invariants.

Example contract regression:

```java
assertEquals(original, codec.decodePlan(json.write(original)));
assertFalse(domain.validation(unselectedDraft).canSubmit());
assertEquals("MODEL_REQUIRED", domain.validation(unselectedDraft).issues().get(0).code());
```

Run: `./mvnw -Dtest=GenerationPlanContractTests,GenerationPlanDomainTests test`.

## Task 2: Java persistence, transport and business integration

**Files:** Create generation/GenerationPlanEntity.java, GenerationPlanRepository.java, GenerationPlanService.java and GenerationPlanController.java; add db/session-storage/V2__generation_plans.sql. Modify SessionEntity/ChatRepository/SessionService, TaskEntity/TaskRecord/TaskRepository/TaskService, HttpIdempotency, ConversationContent/ContentValidator/EventProtocol and focused tests.

**Interfaces:** Expose the eight named tool routes and original receipt lookup defined by the specification. Reuse HttpIdempotency and DatabaseTransactions. Add scoped frontend plan/focus reads and typed notification variants; preserve legacy tool decoding.

- [x] Add persistence/service tests for focus CAS, plan CAS, ownership, immutable frozen reuse and original receipt replay.
- [x] Persist explicit plan snapshots, Session focus and Task plan_id with owner/Session constraints in a forward migration.
- [x] Implement create/read/update/target/clone/focus/submit in one owner/Session lock order. Store both successful results and proven business rejections in typed parent receipts.
- [x] Atomically create N child Tasks/N Outbox commands and freeze once; rollback creates neither children nor frozen state. Recover parent submissions by receipt membership rather than Task operation equality.
- [x] Integrate normalized request decoding, Asset ownership/inspection/retention and single-file result verification without changing legacy behavior.
- [x] Verify HTTP decoding, receipt scope/hash mismatch, historical observation replay, pagination, and Hibernate/query wiring.
- [ ] Run real MySQL migration, foreign-key, batch rollback and competing-focus acceptance tests; local Docker could not start either isolated test container.

Example acceptance regression:

```java
assertEquals(firstReceipt, service.invoke(owner, sameInvocation));
assertEquals(3, firstReceipt.tasks().size());
assertEquals(2, frozenPlan.revision());
```

Run targeted generation tests, then `./mvnw verify`; report Docker-dependent limits separately.

## Task 3: Python agent contract and runtime integration

**Files:** Create lux_agent/generation_plan.py and focused tests. Modify java_tools.py, runtime.py, protocol.py/store.py or their actual current equivalents, and README.

**Interfaces:** Consume the exact eight tool inputs and JavaPlanToolOperationReceipt from the shared schema. The model sees arguments only; the transport injects operation and owner/Session identity. New plan results must have canonical Item serializers/decoders agreeing with Java.

- [x] Add Pydantic fixture, HTTP receipt/recovery and runtime regression coverage.
- [x] Implement matching explicit types and typed tool schemas, preserving all discriminators and required nullable fields.
- [x] Replace the exposed opaque submit_generation tool with the plan tool set; retain historical decoding and get_task.
- [x] Persist original receipts before checkpoint completion and reconcile UNKNOWN using the original invocation only.
- [x] Reject dependent same-response groups before any Java invocation. Hydrate authoritative focus/plan/catalog after compaction and before every model request; failed hydration blocks dependent generation.
- [x] Verify tool call/result pairing, default explanation data, batch member display, replay without replacing context with stale snapshots, and legacy decoding.

Example schema regression:

```python
with pytest.raises(ValidationError):
    SubmitGenerationPlanArguments.model_validate({"plan_id": plan_id, "expected_plan_revision": 1})
```

Run `.venv/bin/python -m pytest`, Ruff check and Ruff format check in lux-python-agent.

## Task 4: Connector normalized decoding and adapter routing

**Files:** Create lux_model_connector/generation_plan.py and focused tests. Modify protocol.py, execution.py, store.py/results.py only where integration requires it, and README.

**Interfaces:** Decode ImageGenerationRequest/VideoGenerationRequest and ProviderBinding explicitly. An adapter registry resolves the exact binding and normalized schema/media/profile combination; provider credentials/endpoints remain connector-owned.

- [x] Add normalized-versus-legacy decode, unknown adapter, binding compatibility and immutable preparation/recovery regression coverage.
- [x] Implement explicit Pydantic normalized request/capability structures needed at this boundary. Share fixture contracts, not service internals or remote utility calls.
- [x] Integrate binding-based adapter resolution before possible dispatch. Preserve the complete selected provider payload, account and adapter identity before external calls.
- [x] Reject malformed recognized normalized variants, unknown/unavailable adapter or Asset resolution/result verification paths without falling through to legacy parsing.
- [x] Enforce exactly one file and no auxiliary outputs on normalized paths, preserving legacy provider payload/result behavior. Do not register unsupported adapters merely to make requests pass.
- [x] Verify local typed test adapters/mocked HTTP boundaries, repeated identities, uncertain recovery and old BytePlus execution compatibility.

Example decoding regression:

```python
with pytest.raises(ValidationError):
    decode_generation_request({"request_type": "image_generation_plan_v1"})
```

Run `.venv/bin/python -m pytest`, Ruff check and Ruff format check in lux-python-model-connector.

## Task 5: Cross-module review and verification

- [x] Run shared structural fixtures through Java and both Python decoders; compare canonical wire values and legacy fixtures.
- [x] Review each module against the specification and current trust/transaction boundaries; resolve all demonstrated findings.
- [x] Run all container-independent Java checks/packaging and complete Python suites; separately record external integration skips and the Docker startup block.
- [x] Update README and API documentation with active tools, migration, empty-model behavior, adapter activation and verified limitations.

## Progress

- Implementation is complete across Java, the agent and the connector. Production model and adapter catalogs remain empty, as requested. No live provider calls, deployments, branches, commits, instruction-file edits, lockfile changes or dependency additions were made.
- Java: 228 tests in 37 suites passed, with no failures/errors/skips, using the managed Mockito JAR as an explicit JVM agent. Container-independent `verify` produced the executable JAR; a separate `./mvnw -q -DskipTests verify` also exited successfully.
- Agent: 89 tests passed; Ruff check and format check passed. Connector: 330 tests passed and 46 environment-dependent integration tests skipped; Ruff check and format check passed.
- The shared 16 examples round-trip through Java and both Python boundaries. Java covers all 144 named schema definitions (141 nonscalar types); runtime resource/schema parity is enforced. Scoped artifact checks pass 109 negative cases for removed fields, counts, bindings and other structure rules.
- Final Java and Python reviews report no remaining demonstrated findings. Regression fixes cover nonfocused cloning, conflicting model pixel defaults, consistent read snapshots, legacy normalized-admission bypass, receipt hash-before-decode, strict integer file counts, connector route/account starvation and Unicode nonblank provider bindings without rewriting route identifiers. Initial module development did not uniformly establish a failing test before production edits; these review regressions were demonstrated failing before their fixes.
- The isolated MySQL tests compiled but remain unverified. Docker responded to inspection and creation, then stalled before starting the Ryuk container and, on a bounded retry with Ryuk disabled, the isolated MySQL container. Both runs were stopped and only their own test resources removed. Existing databases and application containers were left unchanged. Re-run `GenerationPlanIntegrationTests` and full `./mvnw verify` when Docker can start new containers.
- Synthetic adapters and local HTTP/media fixtures establish contract behavior; they do not establish real provider/API compatibility or live generation quality. Production activation requires concrete model capabilities, matching Java execution/verifier registrations, an authorized connector Asset locator and an exact registered provider adapter.
