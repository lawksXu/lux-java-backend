# Lux local application stack

This is the unified Docker Compose entrypoint for the existing Java, Agent,
Connector and Frontend repositories. Each service owns its Dockerfile and
dependencies; this directory owns application wiring and local middleware. The
original root `compose.yml` remains a separate middleware-only stack with separate volumes.

## Run all seven services

From `/Users/xuwenjie/lux-java-backend`:

```sh
cp deploy/local/.env.example deploy/local/.env
# Replace the placeholder credentials with private local, URL-safe values.
COMPOSE_PARALLEL_LIMIT=1 docker compose --env-file deploy/local/.env \
  -f deploy/local/compose.yml up --build -d
docker compose --env-file deploy/local/.env -f deploy/local/compose.yml ps
docker compose --env-file deploy/local/.env -f deploy/local/compose.yml logs --tail 100
docker compose --env-file deploy/local/.env -f deploy/local/compose.yml down
```

The `down` command preserves the named data volumes. Keep `.env` private and out
of Git. Password changes do not alter accounts in an existing MySQL volume.
The runtime application ports default to Frontend 18082, Java 18080, Agent 18000
and Connector 18001 on loopback; MySQL 13306, Kafka 19092 and S3 18333 can be
configured independently. Open the Frontend at `http://127.0.0.1:18082`.
Internal calls always use service DNS and container ports. Real OpenAI and
BytePlus execution are disabled and no provider keys are included. Without an
executor, submitted Agent input receives the existing `MODEL_NOT_CONFIGURED`
outcome; deterministic generation acceptance uses the integration override below.

Kafka uses a thin derivative of `apache/kafka-native:4.0.2` that creates its
persistent log directory for the upstream image's UID1000. Broker binaries and
the non-root runtime user remain upstream; no root startup or shared-volume
permission change is needed.
The upstream image's configuration and secrets paths also use explicitly
owner-labeled named volumes so every persisted mount is recorded for cleanup.

Java and Connector use separate schemas. Agent's whole Store/checkpoint directory
is persistent. Python uses one non-root worker and Linux FFmpeg/FFprobe with
`fd` support; Java also includes FFprobe. Healthchecks report process/endpoint
availability, while the acceptance driver checks actual transport and workflow
readiness. Kafka-native's lightweight TCP check does not prove metadata or topic
readiness by itself.

If Docker Desktop's usual socket stalls startup in this environment, select the
working raw daemon socket without restarting shared services:

```sh
export DOCKER_HOST="unix://$HOME/Library/Containers/com.docker.docker/Data/docker.raw.sock"
```

Build and run only one full test stack at a time on the current approximately
3.8 GiB Docker VM. `COMPOSE_PARALLEL_LIMIT=1` serializes service builds; each
service has an explicit runtime memory limit.

## Production frontend container

Keep `lux-frontend` beside the three existing service repositories. Its Dockerfile
builds the React application with Node 22 and serves the resulting static files
through Nginx as a non-root user. The runtime filesystem is read-only; temporary
Nginx files use the bounded `/tmp` mount. The container receives no provider keys
or backend credentials.

The browser uses same-origin `/api` requests. Nginx forwards them to
`http://java-backend:8080`, including SSE without response buffering, large
attachment uploads and media byte-range responses. The Java host port does not
affect this internal route. `/health` checks the frontend server; Java readiness
is checked separately before Compose starts the frontend. This container replaces
the development Vite server for normal Compose use.

To add or rebuild only the frontend in an existing `lux-dev` stack that uses
generated extras and private provider configuration, run from the Java checkout:

```sh
COMPOSE_PARALLEL_LIMIT=1 docker compose -p lux-dev \
  --env-file deploy/local/.env --env-file deploy/local/.env.providers \
  -f deploy/local/compose.yml \
  -f target/local-stack/compose.extras.yml \
  -f deploy/local/.env.providers.compose.yml \
  build frontend
docker compose -p lux-dev \
  --env-file deploy/local/.env --env-file deploy/local/.env.providers \
  -f deploy/local/compose.yml \
  -f target/local-stack/compose.extras.yml \
  -f deploy/local/.env.providers.compose.yml \
  up -d --no-build --pull never --no-deps frontend
```

Reuse the current project's name and Compose files. Omit the generated extras
line if that stack does not use them, and omit both private provider file
arguments when real providers are disabled. The frontend-only commands leave
the running application services and their data volumes in place. Configure
`LUX_FRONTEND_PORT` in the private base env file to change the loopback port.
The generated extras can add Redis to the seven-service base stack.

## Enable real providers in the existing local stack

Real OpenAI and BytePlus calls can incur charges. The base Compose file keeps
execution disabled; enable it with private files instead of editing that default.
Create `deploy/local/.env.providers` and
`deploy/local/.env.providers.compose.yml` with mode `0600`. Both paths are ignored
by Git. Set the following variables in `.env.providers`, supplying the keys
privately:

```dotenv
OPENAI_API_KEY=
LUX_BYTEPLUS_API_KEY=
LUX_OPENAI_ENABLED=true
LUX_CONNECTOR_PROVIDER_EXECUTION_ENABLED=true
LUX_BYTEPLUS_GENERATION_ENABLED=true
```

Use this content for `.env.providers.compose.yml`:

```yaml
services:
  java-backend:
    environment:
      LUX_BYTEPLUS_GENERATION_ENABLED: ${LUX_BYTEPLUS_GENERATION_ENABLED:-false}
  agent:
    environment:
      LUX_OPENAI_ENABLED: ${LUX_OPENAI_ENABLED:-false}
      OPENAI_API_KEY: ${OPENAI_API_KEY:?Set OPENAI_API_KEY}
  model-connector:
    environment:
      LUX_CONNECTOR_PROVIDER_EXECUTION_ENABLED: ${LUX_CONNECTOR_PROVIDER_EXECUTION_ENABLED:-false}
      LUX_BYTEPLUS_API_KEY: ${LUX_BYTEPLUS_API_KEY:?Set LUX_BYTEPLUS_API_KEY}
```

From the repository root, recreate only the three application services using the
existing local images and the same `lux-dev` project:

```sh
COMPOSE_PARALLEL_LIMIT=1 docker compose -p lux-dev \
  --env-file deploy/local/.env --env-file deploy/local/.env.providers \
  -f deploy/local/compose.yml \
  -f target/local-stack/compose.extras.yml \
  -f deploy/local/.env.providers.compose.yml \
  up -d --no-build --pull never --no-deps java-backend agent model-connector
```

Keep `target/local-stack/compose.extras.yml` when it was used to start the current
stack; omit that line for a stack originally started without generated extras.
Reuse the original project name if it differs from `lux-dev`. This command leaves
middleware services and data volumes in place. Do not reset volumes to apply keys.
Enabling the Connector starts its provider worker and can process eligible
persisted Tasks. Reconcile an uncertain provider submission using its original
IDs before considering another submission. Healthchecks establish service
readiness; an actual request is needed to verify provider account access.

## Deterministic complete container acceptance

Build the current images serially, then use a fresh private artifact directory:

```sh
COMPOSE_PARALLEL_LIMIT=1 docker compose --env-file deploy/local/.env \
  -f deploy/local/compose.yml build kafka java-backend agent model-connector
../lux-python-agent/.venv/bin/python scripts/integration/compose_acceptance.py run \
  --directory /private/tmp/lux-compose-acceptance
```

The driver uses this Compose model plus `compose.integration.yml` with a unique
project, private generated credentials, UUID schemas, owned volumes and a local
provider fixture. The override keeps the frontend behind the optional `frontend`
profile, preserving the existing eight-service acceptance model without requiring
the frontend checkout. All three business services and middleware run as containers.
Only model/provider HTTP is substituted; production SDK clients, workers,
persistence and Kafka/S3 adapters still execute. No host business process or
Docker socket mount is used. The host driver sends test HTTP and issues
ownership-checked container lifecycle/inspection commands.

The assertions cover actual image/video bytes and model image/frame blocks,
plans/Tasks/Assets, original operations and normalized requests, owner scope,
SSE/range reads, invalid Seedance reference admission, lost original IDs,
authenticated evidence attachment and original GET without another provider
POST. Additional restarts verify immutable Agent checkpoint contents, later
input in the same Session, and Java Task/Asset/SSE persistence. Private evidence
and logs are retained; cleanup affects only that recorded project after evidence
capture. The driver stops on ownership failure, OOM or unexpected provider
submission duplication.

These checks establish local container/network/durability behavior. Live account
access, provider output quality, deployment and billing wait for API keys and a
separately agreed scope. The successful default startup and full generation/restart
run are recorded in the [container acceptance report](../../docs/superpowers/plans/2026-10-13-compose-container-integration-report.md).
The broader failure matrix remains in the
[integration report](../../docs/superpowers/plans/2026-10-13-cross-project-integration.md).

## Repository layout decision

The user selected retaining separate repositories for this implementation.
Dockerfiles stay with their services and relative Compose build contexts select
each sibling checkout without sending the whole parent directory as context.
Docker documents [relative build contexts](https://docs.docker.com/reference/compose-file/build/)
and [service-name networking](https://docs.docker.com/compose/how-tos/networking/).

For shared maintenance/release ownership and frequent coordinated contract
changes, a future monorepo can provide atomic cross-service changes and one
clone/run/test entrypoint:

```text
lux/
  compose.yml
  compose.integration.yml
  services/
    java-backend/
    agent/
    model-connector/
  packages/
    python-utils/
  tests/integration/
  docs/
```

Service isolation and state ownership remain the same in that layout. When
independent repositories/releases must remain, a neutral `lux-stack` repository
can own Compose, environment examples and acceptance scripts instead. Git
submodules add checkout/version-update overhead and are not needed for the
current sibling-checkout arrangement. No files or Git histories are migrated
by this task.
