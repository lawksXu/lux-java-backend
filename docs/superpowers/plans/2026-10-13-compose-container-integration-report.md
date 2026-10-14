# Lux Compose Container Integration Acceptance

Executed on October 13, 2026 in the local America/Los_Angeles timezone. Both the
default six-service startup and complete deterministic container acceptance passed.
The driver returned exit code 0 and retained evidence before exact owned cleanup.
Real OpenAI and BytePlus calls were not executed.

## Delivered layout and execution

The existing repositories remain separate. Each business repository owns its
Dockerfile and narrow source context; `deploy/local/compose.yml` owns unified
wiring for Java, Agent, Connector, MySQL, Kafka and SeaweedFS S3. The original root
middleware-only Compose stack remains a separate entrypoint. Python Utils is not
a required service in this generation loop.

Java uses Java 17 and its existing Maven wrapper. Python images use Python 3.14,
one Uvicorn worker and installed project dependencies. All three business services
run as UID 10001, bind to `0.0.0.0` and communicate through container service DNS.
Store/checkpoints, database, broker and object bytes use named volumes. FFprobe is
available in each business image; Python FFmpeg/FFprobe include the required `fd`
protocol. The thin Kafka image fixes the upstream non-root log-directory permission
and explicitly owns all three inherited persistent mount paths.

The test override adds one provider HTTP fixture and one completed initialization
container. Production application lifecycles, SDK clients, SQL, Kafka and S3 adapters
execute inside the business containers. The host runs assertions and Docker control
only. No Docker socket is mounted in a service, and no host business process stands
in for a container.

## Actual verification

| Gate | Executed evidence |
| --- | --- |
| Image builds | Java, Agent, Connector and thin Kafka images built serially; all build commands exited 0 |
| Default startup | Six services healthy with the ordinary image commands, providers disabled; Python `pip check`, FFprobe and writable application paths passed |
| Image generation | Authorized reference -> exact Seedream Lite plan -> Connector -> retained PNG -> Java Task/Asset import -> real SDK image observation |
| Video generation and missing ID | Exact Seedance plan -> lost acceptance response -> original Connector restart -> authenticated original ID attachment -> original polling GET -> verified MP4 import |
| Model missing ID | Original OpenAI create outcome unresolved -> original Agent restart -> authenticated original response ID attachment -> original response GET -> original Run completion |
| Observation after Agent restart | All 210 prior checkpoint keys and checkpoint/metadata blob hashes retained; 219 rows afterward; later input in the existing video Session received verified original video frames |
| Java restart | Original Task/Asset identities, full/ranged bytes and SSE replay remained available after restarting the same container |
| Negative boundaries | Wrong-owner Asset and Session-scoped Task reads denied; unauthenticated reconciliation reads and mismatched Connector receipt attachment denied; invalid 751x300 Seedance reference rejected before execution |
| Cross-database closure | 2 successful Tasks, 2 imported Assets, 2 frozen plans, 2 successful Connector executions; 9 delivered Java Outbox rows, 145 applied Inbox rows; 1 reconciliation audit in Agent and 1 in Connector |
| No resubmission | Image POST 1, video POST 1, original video GET 1, original response GET 1; the lost OpenAI execution had exactly 1 create POST |
| Owned cleanup | All three attempted test projects have no remaining containers, networks or volumes after cleanup/reconciliation |

Successful model invocations may contain multiple distinct tool turns. The single
POST assertion applies to each original generation or the unresolved model
execution, not to the whole conversation. Frame counts in fixture summaries are
cumulative across invocations; each video observation remains bounded to eight
presentations. No new generation Task/Asset/plan or generation POST was introduced
by the later observation after Agent restart.

Restart records retain each original full container ID and record a changed
`StartedAt`, with the original image, mounts and networks preserved. All three
restarted business containers were running afterward without OOM:

| Service | Original container ID | Runtime image ID |
| --- | --- | --- |
| Java | `8a7aaf93df41af8c247af3f0f3a96e2dc352c96edf3de0d9afb4875f483ac615` | `sha256:d2c2da580945b79ed928f3f654b552fd51ad44e8810178600ac246a870575369` |
| Agent | `4ed892ac0b5191b29c9314ec6eb4b6467b4d49f718ebc56308c9d88043ce4544` | `sha256:ce3eb22eaa0ed3c728bd2a65406136d1eca8c8fabe41c310523545af12218657` |
| Connector | `f83c1db866400dee21f350d42390e45a29162a7eee8dd2354a4cbc2a50cdc9ba` | `sha256:2ebf3ea7a419323cff114355b349776b0428f07ef3936b51b2c1e6664d519b19` |

The scoped orchestration suite passed 14 tests, and Ruff check/format passed for
`scripts/integration`. These tests protect actual control and evidence boundaries:

| Regression | Protected behavior |
| --- | --- |
| Owner mismatch | No restart or cleanup control before owner validation |
| Changed image | No read-only container execution or restart after image identity changes |
| Uncertain restart | Reconcile the original ID after timeout without issuing another restart |
| Unchanged restart timestamp | Stop when an uncertain restart cannot be established |
| Checkpoint evidence | Require every prior key and exact blob hashes while allowing new rows |
| Model inspection | Read original receipt/audit from the existing Store; reject a missing Store |
| Created-container network | Discover and record the owned network before startup |
| Unowned volume | Validate the whole cleanup scope before the first removal |
| Fixture OOM | Stop host assertions when the fixture cannot preserve receipt evidence |
| Partial creation | Record and clean owned volumes/networks even when no container mounts them |
| Foreign partial resource | Reject a foreign-owned resource before control |
| Existing project collision | Reject an occupied project scope before creation |
| Truncated inventory ID | Persist canonical full IDs and use them for capture, restart and cleanup |
| Mount array order | Accept reordered identical mounts; still reject changed source/name/writability |

These are the new packaging/orchestration checks. Earlier host component suites
remain separate evidence and were not represented as unit suites executed inside
the new images. The full container scenario exercised the freshly installed Linux
SDK/dependency versions, including OpenAI SDK 3.30.0.

## Runtime corrections and retained artifacts

The first default startup attempt stopped before service startup because inventory
IDs were truncated and Kafka's inherited anonymous mounts lacked ownership labels.
Those failures were corrected with canonical inspected IDs and explicitly labeled
named mounts. Docker also reorders mount arrays; identity decoding now canonicalizes
order without dropping any field check. Partial-create cleanup independently records
unmounted owned networks/volumes and prevalidates the complete scope.

The original failed attempt's FAIL result is preserved. A narrowly bound recovery
validated the exact original never-started containers and two original Kafka
anonymous volumes before removing them. Subsequent fresh default and full runs
passed normal owned cleanup. Final read-only inventory confirms all three test
project scopes are empty. The four pre-existing shared container records remain;
they are currently exited, not running, and finished before the full acceptance
started. No shared restart is part of this workflow.

Private machine-local evidence:

- `/private/tmp/lux-compose-default-20261013-r2`: default startup result, six-service checks, image identities, captured states and empty project after cleanup.
- `/private/tmp/lux-compose-integration-20261013-r1`: PASS result, acceptance/database/checkpoint evidence, manifest, restart records, service logs and cleanup evidence.
- `/private/tmp/lux-compose-default-20261013-r1`: original failed attempt and exact recovery validation/cleanup evidence.
- `/private/tmp/lux-compose-final-inventory.json`: final read-only empty project inventories and preserved shared container identities/states.
- `/private/tmp/lux-compose-{java,agent,connector,kafka}-build.log`: completed serial build logs.

Configuration and manifests include private test credentials. Keep their mode 700
directory / mode 600 files private; do not publish complete evidence directories.
The reusable commands are in the [local stack guide](../../../deploy/local/README.md)
and [integration scripts guide](../../../scripts/integration/README.md).

## Remaining limits

This closes the executed local container generation, observation, durability and
original-receipt recovery paths. Real provider account/model activation, output
quality, billing, deployment and production object storage wait for API keys and
an agreed live scope. The broader prepared fault matrix in the
[cross-project report](2026-10-13-cross-project-integration.md) is not all executed;
arbitrary crash, rebalance and storage-fault combinations remain separate cases.

Runtime base tags and Python transitive dependencies are not immutable locks.
Recorded image IDs establish the images tested here; later rebuilds may resolve
different versions and should repeat acceptance. Existing schemas with the old
Flyway V1 checksum still require an explicit migration decision; this task neither
repairs nor resets them.

For services with one release/maintenance lifecycle, a future monorepo can make
cross-service changes atomic and provide one clone/build/test entrypoint. This
task implements the user's selected multi-repository arrangement with one Compose
entrypoint and does not move directories or Git histories.
