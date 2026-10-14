# Local integration acceptance

## Complete Docker Compose acceptance

The [unified local stack](../../deploy/local/README.md) runs Java, Agent, Connector,
MySQL, Kafka and S3 as containers. The deterministic integration override replaces
only provider HTTP and reuses the business assertions below. Build the images
serially, then run from the Java checkout with a new private evidence directory:

```sh
COMPOSE_PARALLEL_LIMIT=1 docker compose --env-file deploy/local/.env \
  -f deploy/local/compose.yml build
../lux-python-agent/.venv/bin/python scripts/integration/compose_acceptance.py run \
  --directory /private/tmp/lux-compose-acceptance
```

Create the private `.env` as documented in the stack guide first. The host driver
requires the existing Agent Python environment; Java, FFmpeg and business workers
run inside their images. The driver allocates a unique Compose project and ports,
checks recorded owner/container/image/mount/network identities before control,
captures private evidence, and removes only its resources on completion or failure.
Never overlap this stack with the process/component test stacks on this Docker VM.

The [container acceptance report](../../docs/superpowers/plans/2026-10-13-compose-container-integration-report.md)
records the successful default six-service startup and full generation/restart run.

## Host-process acceptance

These scripts create disposable local infrastructure and run the real Java backend,
Agent and Connector against it. OpenAI and BytePlus HTTP endpoints are replaced by
a deterministic loopback fixture; their production clients, workers, persistence,
Kafka transport and S3 adapters still run. This does not prove live provider acceptance,
cloud storage behavior, deployment or billing.

Requirements: Docker, Java17, FFmpeg/FFprobe, the supported Agent and Connector
`.venv` environments, and a current packaged Java jar. No provider credentials are
required. The scripts use fixture credentials and inherit only OS/runtime variables.

For Docker Desktop installations where normal container starts remain `Created`,
select its working raw daemon socket without restarting shared Docker services:

```sh
export DOCKER_HOST="unix://$HOME/Library/Containers/com.docker.docker/Data/docker.raw.sock"
python3 scripts/integration/local_services.py start /private/tmp/lux-component-acceptance
(cd ../lux-python-model-connector && \
  LUX_CONNECTOR_TEST_CONFIG=/private/tmp/lux-component-acceptance/connector-test.json \
  .venv/bin/python -m pytest)
python3 scripts/integration/local_services.py stop /private/tmp/lux-component-acceptance
python3 scripts/integration/local_services.py start /private/tmp/lux-process-acceptance
../lux-python-agent/.venv/bin/python scripts/integration/process_acceptance.py run \
  --services /private/tmp/lux-process-acceptance \
  --java-jar target/lux-java-backend-0.0.1-SNAPSHOT.jar
python3 scripts/integration/local_services.py stop /private/tmp/lux-process-acceptance
```

Build the jar with `./mvnw verify` first. For Testcontainers with this Docker Desktop
workaround, also set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
Run the Connector pytest command from its checkout if its tests require local imports.
Each Connector test creates and drops only its UUID-named fixture schema.

Run component suites and process acceptance sequentially, using a fresh broker for
each process acceptance run. The harness refuses brokers with prior Lux topic history.
The launcher caps MySQL at 768 MiB, Kafka at 512 MiB and S3 at 384 MiB; avoid overlapping
Testcontainers stacks on a Docker VM with limited memory.

The launcher allocates loopback ports, labels its own containers, persists identities
before starting, and writes mode600 manifests under a mode700 directory. Stop reads
that manifest and verifies ownership before removing the exact containers and their
anonymous volumes. It never prunes Docker or edits shared schemas/volumes. A failed
launch retains its manifest for inspection and cleanup instead of recreating resources.

The process harness creates UUID-named backend/connector schemas and private process
logs, configuration, PID manifests and acceptance evidence beneath the service directory.
It covers image/video tool plans, normalized Tasks, byte retention/import, owner scope,
SSE/range reads, invalid reference admission, actual model image/frame inputs, and lost
original receipts with authenticated attachment/restart/original GET without another POST.
All subprocesses stop on completion or failure. Infrastructure stays running for review
until the explicit launcher stop command removes the owned resources. Retain evidence
before cleanup; disposable schemas and object bytes disappear with their container volumes.
