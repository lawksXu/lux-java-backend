# Lux Compose Container Integration Design

Status: approved scope. The user requested all three business services and MySQL/Kafka/S3 under Docker Compose, then explicitly selected retaining the existing repositories with one Compose entrypoint. No repository migration, branch, commit or instruction-file edit is authorized.

## Repository arrangement

Each existing repository owns its Dockerfile and narrow build context. `/Users/xuwenjie/lux-java-backend/deploy/local/compose.yml` is the unified application entrypoint; `compose.integration.yml` adds only deterministic local provider HTTP and acceptance setup. Keeping the orchestration here is the smallest current change because integration scripts already belong here. The original middleware-only root Compose file and its existing volumes remain separate.

For a future coordinated repository migration, a single `lux` repository containing `services/java-backend`, `services/agent`, `services/model-connector`, `packages/python-utils`, top-level Compose, integration tests and documentation is the preferred arrangement when these components share release/maintenance ownership. Services retain separate Maven/Python builds, images, processes and state ownership. An independent `lux-stack` integration repository is an alternative when separate Git histories/releases must remain. Git metadata/history sources must be established before either migration; none occurs in this task.

## Runtime and service wiring

- Business services: `java-backend:8080`, `agent:8000`, `model-connector:8001`. All servers bind `0.0.0.0`; Python uses one process/worker without reload.
- Infrastructure: `mysql:3306` (8.4), `kafka:9092` (native4.0.2), `seaweedfs:8333` (4.47). Kafka advertises internal service DNS; optional published host ports use a separate external listener. Broker/clients retain the existing32MiB record/request and64MiB aggregate fetch budget.
- Kafka's official native image uses UID1000 and does not precreate `/var/lib/kafka/data`. An actual mounted-directory probe reproduced the root-owned unwritable volume. A thin derivative creates/chowns that directory at image build time and preserves the upstream non-root runtime and4.0.2 binaries; no shared volume permissions are changed.
- Java and Connector have separate fresh schemas. MySQL startup provisions only application-owned fresh schemas/users and512MiB packet capacity. No existing schema or Flyway checksum is repaired/reset.
- Agent retains its complete writable Store/checkpoint/lock directory; MySQL/Kafka/S3 and Java staging/data directories use project-owned volumes. Python runtime images use Python3.14 slim-trixie, FFmpeg with the `fd` protocol, and an explicit non-root user. Java uses Java17 and real FFprobe.
- Healthchecks establish container process availability and startup ordering; `/health` is not represented as proof of all external dependencies. Acceptance requires actual workflow and persistence assertions.
- Runtime credentials are supplied outside Git. Production model/provider flags default disabled. Integration overrides use fixture-only credentials and existing exact-host HTTP redirects; no real model/provider call is made.

## Acceptance ownership

Reuse the existing provider fixture and business scenario assertions. Introduce a test-only typed lifecycle boundary so the original host-process driver and new Compose driver differ only in restart/inspection operations. Container workers receive DNS endpoints; host HTTP assertions receive published loopback endpoints. Never mix these views.

The Compose driver owns a unique project name and private manifest/evidence directory. It records exact container/image/network/volume identities and validates owner plus Compose labels before restart or removal. Setup copies private fixture/configuration artifacts into an owned volume with the runtime user's permissions, then creates only UUID-named application schemas. The provider fixture remains alive during business-container restarts so original receipts and attempt counters remain available.

Required assertions: image/video input and result observation through actual ChatOpenAI serialization; normalized Task and immutable Java/Connector request/operation equality; retained/imported bytes and hashes; scope/SSE/range/ratio rejection; lost Seedance and OpenAI ID followed by restart, authenticated original evidence attachment and original GET with one initial POST; unchanged checkpoint keys/blob hashes across Agent restart, later input in the same existing video Session; Java restart retaining Task/Asset identities and bytes. All business and middleware components must be Docker containers, not host processes.

Build images sequentially and run one owned stack within the current approximately3.8GiB Docker VM. Capture logs/state on failure and stop on OOM, unexpected provider POST duplication or ownership failure. An uncertain provider acceptance never causes scenario resubmission. Cleanup removes only recorded owned containers/networks/volumes after retaining evidence. Shared services, historical Created containers and original Compose volumes remain untouched.

## Verification limits

Compose configuration parsing, image build and healthchecks are separate from successful business integration. Report the actual container IDs/images, restart persistence, attempt counters and executed assertions. The broader prepared failure matrix remains separate; do not claim unexecuted crash/rebalance combinations passed. Live account activation, output quality, cloud/deployment and billing wait for keys and a separately agreed scope.
