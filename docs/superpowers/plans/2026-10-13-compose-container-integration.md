# Lux Compose Container Integration Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement the independent image and harness tasks, then review and execute the complete container stack sequentially.

**Goal:** Run Java, Agent, Connector, MySQL, Kafka and S3 as Docker Compose containers and verify the complete deterministic local generation/recovery loop.

**Architecture:** Retain the three existing repositories. Each owns its runtime Dockerfile; one Compose entrypoint and an integration override own local orchestration. Reuse the previous production-process fixture/assertions through a typed test lifecycle boundary, with real container DNS, persistence and restart operations.

**Tech Stack:** Java 17/Maven, Python 3.14, Docker Compose 2.39.1, MySQL 8.4, Kafka-native 4.0.2, SeaweedFS 4.47, FFmpeg/FFprobe, existing FastAPI/SDK clients.

## Global Constraints

- User selected retaining multiple repositories. No directory/history migration, branch, commit, instruction-file modification, live provider call, cloud write or shared-data reset.
- Source changes remain confined to Docker packaging and test orchestration; business ownership, protocol formats, immutable receipts and unknown-outcome rules remain unchanged.
- Known structures use named types. Documentation English, Java explanatory comments Chinese. Prefer existing dependencies and one Maven module.
- Business containers bind0.0.0.0 and use service DNS. One Agent process retains the complete Store/checkpoint directory. Fixture HTTP is the only substituted external boundary.
- Fresh owned schemas/project/network/volumes; label/ID-checked cleanup only. Build and execute serially within the3.8GiB VM.
- User's explicit approved choice supplies architecture approval; continue implementation without asking for duplicate approval. Spec/plan are saved for review, without the skill's suggested commits because user prohibits commits.

## Task1: Python runtime images

Files: create `/Users/xuwenjie/lux-python-agent/Dockerfile`, `.dockerignore`, and corresponding Connector files; update each README's container section.

- [x] Use `FROM python:3.14-slim-trixie`, install `ffmpeg` and verify `ffmpeg -protocols` contains`fd`, install the existing project from`pyproject.toml` in a build stage.
- [x] Exclude `.venv`, credentials, Git metadata, local data, build/dist/cache and generated files from image contexts. No lock/dependency changes are required for packaging.
- [x] Create uid/gid10001 and writable owned `/var/lib/lux-agent` or `/var/lib/lux-model-connector`; default USER10001. Use exec-form single-worker Uvicorn commands bound0.0.0.0, ports8000/8001.
- [x] Validate each actual built image by health/startup, `pip check`, `ffmpeg -protocols` and the complete container media workflow. Root owns serialized build/start commands; implementer does not start overlapping stacks.

## Task2: Java runtime image

Files: create `/Users/xuwenjie/lux-java-backend/Dockerfile`, `.dockerignore`; root updates README.

- [x] Multi-stage Java17 JDK/JRE build using the existing Maven wrapper. Copy only wrapper/pom and then source, cache Maven downloads, run`./mvnw -B -ntp -Dmaven.test.skip=true package`; runtime installs FFmpeg/FFprobe and healthcheck HTTP tooling.
- [x] Copy only the built executable jar into runtime; USER10001 with writable data/staging directory. Set`LUX_BIND_ADDRESS=0.0.0.0`; exec-form`java -jar /app/app.jar`.
- [x] Keep generated wrapper files unchanged. Exclude target, private data/env, Git/IDE state and caches from source context. Root verifies image startup/actual Java business HTTP rather than inventing image-only assertions.

## Task3: Compose application entrypoint

Files: create `/Users/xuwenjie/lux-java-backend/deploy/local/compose.yml`, `.env.example`, `mysql-init.sql`, `kafka/Dockerfile`, `kafka/.dockerignore`, README; update root README links.

- [x] Define exactly the six application services, relative narrow contexts, named persistent volumes, loopback host ports and service DNS configuration. Agent/Connector build contexts are sibling directories. Preserve the existing root`compose.yml` resources.
- [x] Configure authenticated MySQL readiness, explicit packet capacity, Kafka DNS/record budgets and S3 credential/bucket initialization. Use healthy/completed startup gates without Java/Agent dependency cycles.
- [x] Verify Kafka persistent-volume write permission as its actual UID1000. The demonstrated upstream failure is corrected by a thin image that precreates/chowns the log directory, then restores`USER appuser`; validate the mounted path again before full startup.
- [x] Keep real provider execution disabled by default; runtime keys are absent from image build and examples. Document`docker compose -f deploy/local/compose.yml up --build -d` and owned local stop semantics.
- [x] Parse with`docker compose ... config --quiet`; build sequentially with`COMPOSE_PARALLEL_LIMIT=1`; inspect real runtime/non-root/limits/network state. The integration driver uses a fresh unique project instead of starting the stable developer project.

## Task4: Compose lifecycle and full acceptance

Files: modify `/Users/xuwenjie/lux-java-backend/scripts/integration/process_acceptance.py`; create `compose_acceptance.py` and explicit shared test types/inspectors only where necessary; create `/Users/xuwenjie/lux-java-backend/deploy/local/compose.integration.yml`. Reuse`provider_fixture.py` and`database_acceptance.py`; avoid duplicating the scenario.

- [x] Add named lifecycle operations for restarting Agent/Connector and inspecting original model/DB evidence; preserve the original process driver. Add test-worker bind argument default127.0.0.1; Compose passes0.0.0.0.
- [x] Compose service names are`java-backend`, `agent`, `model-connector`, `mysql`, `kafka`, `seaweedfs`; integration adds`fixture` and one-shot`integration-init` only. Root owns base Compose; harness implementer owns integration override/driver and coordinates image paths/permissions.
- [x] Host assertion config uses published localhost URLs; container worker config uses`http://java-backend:8080`, `http://agent:8000`, `http://model-connector:8001`, `http://fixture:8003`, `mysql:3306`, `kafka:9092`, `http://seaweedfs:8333`. Config/media volume is private and read-only in workers after trusted one-shot initialization.
- [x] Use exact recorded owned container IDs for restarts and capture changed`State.StartedAt`; retain network/image/mount identities and original consumer groups. Never mount the Docker socket into application containers.
- [x] Execute existing image/video/lost-ID/negative/SSE/range/SQL assertions. Capture read-only checkpoint key/blob hashes before/after Agent restart; admit later observation in the same video Session; restart Java and verify unchanged Task/Asset/bytes/SSE. Provider POST counters remain one per generation/original lost-model execution.
- [x] Capture private container manifest, logs, sanitized acceptance/checkpoint/database evidence and final status. On success/failure remove only exact owned Compose resources after saving evidence; record remaining shared inventory.
- [x] Run scoped Ruff/format and meaningful test orchestration regressions. New assertions are real acceptance checks, not tests of trivial accessors. Independently review image tasks, lifecycle wiring and final complete source/evidence.

## Completion ledger

- [x] Python images packaged and actual images verified.
- [x] Java image packaged and actual image verified.
- [x] Unified Compose config and initialization validated.
- [x] Full container acceptance and restart/persistence assertions passed.
- [x] Independent review resolved; owned resource cleanup/evidence documented.

## Executed evidence

All completion gates passed on October 13, 2026. Four image builds, the default
six-service startup, the complete container generation/recovery scenario, 14 scoped
orchestration regressions and Ruff check/format passed. Independent source and
retained-runtime-evidence review has no open findings. All three attempted test
projects have empty container/network/volume inventories after exact cleanup.

The [acceptance report](2026-10-13-compose-container-integration-report.md) records
the actual image IDs, three original-container restarts, unchanged prior checkpoint
hashes, provider attempt counters, cross-database counts, artifacts and limits.
Real provider calls and the broader unexecuted fault matrix remain separate gates.
