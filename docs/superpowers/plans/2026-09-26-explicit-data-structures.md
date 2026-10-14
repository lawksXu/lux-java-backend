# Explicit Data Structures Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the cohesive Java migration and independent review. The user already approved the design direction and requested execution. Do not create commits or branches.

**Goal:** Apply a shared explicit-type rule to all four Lux repositories and remove known-schema JSON trees and maps from Java business contracts.

**Architecture:** Keep transport parsing and raw receipts at adapters; introduce feature-owned records/variants for stable schemas. Retain only documented genuinely dynamic or opaque fields inside typed structures.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Jackson 3, Maven, existing JUnit/Mockito/Testcontainers; Python guidance only.

## Global Constraints

- Preserve external HTTP/Kafka JSON names, optional-field semantics, database JSON formats, hash/idempotency semantics, event sequencing, ownership checks, locking, retry classification, and transactional behavior.
- No new dependencies, database migrations, Git branches, or commits.
- Java var is prohibited. Use Chinese explanatory Java comments and English documentation.
- Do not replace JsonNode with schema-shaped Map, Object, or a wrapper that still makes business components inspect string keys.
- Current workspace has no usable Git checkout; compare against /private/tmp/lux-typed-structures-20260926/baseline.

## Task 1: Shared guidance

- [x] Read all four existing AGENTS.md files.
- [x] Add the English explicit-data-structures section to each root guide.
- [x] Snapshot Java sources, tests, docs and build files before editing.

## Task 2: Java migration and focused verification

**Task brief:** /private/tmp/lux-typed-structures-20260926/java-task-brief.md

**Files:** existing chat, task, infrastructure, and common Java packages; associated tests; new cohesive model/codec files in their owning packages. Preserve unrelated asset/user code.

**Interfaces:** Introduce List<ContentBlock> for message contents, List<ResultSource> for task results, typed event envelopes and per-event interpreted payloads, typed commands and notifications, and typed idempotent business responses. Model names may follow the nearest maintained feature conventions. Boundary parsers consume raw JSON and produce those structures; generic serialization and raw receipt persistence remain infrastructure concerns.

- [x] Inspect full caller/persistence/transport flow and the API contract before editing.
- [x] Add meaningful compatibility/regression tests before migrating their production paths; record the initial results.
- [x] Migrate message content and typed task result sources, preserving content restrictions and import validation.
- [x] Migrate event envelopes/payloads and known outbound command/notification structures; preserve durable receipt evidence and failure classification.
- [x] Migrate fixed hash inputs and controller responses/HTTP idempotency to explicit contracts.
- [x] Adapt all affected existing tests without removing unique scenarios.
- [x] Run focused tests per change, then ./mvnw verify once; explain skipped tests.
- [x] Audit remaining JsonNode, ObjectNode, ArrayNode, Map, Object and var usages and document justified exceptions.

## Task 3: Independent review and final verification

- [x] Produce a diff against the pre-edit snapshot and a concise implementer report.
- [x] Review schema compatibility, input validation, raw event identity, optional fields, hash stability, and legacy persisted data.
- [x] Correct actionable issues and run the checks covering fixes.
- [x] Verify all four guides contain the same rule and report concrete results and remaining runtime limits.

## Verification evidence

- Baseline non-container verify: 98 tests, zero failures/errors; packaged successfully.
- Final non-container verify after compatibility fixes: 112 tests, zero failures/errors; packaged successfully. Command: `./mvnw '-Dtest=!LuxBackendApplicationTests,!*IntegrationTests' verify`.
- Full `./mvnw verify`: attempted before and after migration; Testcontainers stalled starting its Ryuk helper. The final attempt was stopped by a 120-second process-group timeout before any test executed. Docker-dependent acceptance is not verified.
- Independent review found a legacy numeric/boolean source-filename compatibility regression. Added persistence/import regressions and a compatible typed persisted projection; new ingress remains strict. Final independent re-review approved the fix. A subsequent unexpected-source guard was restored and covered by a failing-then-passing infrastructure regression; the final focused run passed all 16 tests.
