"""Run the existing deterministic acceptance against an exclusively owned Compose stack.

Run from the Agent virtual environment after building the three :local images:
  python scripts/integration/compose_acceptance.py run --directory PRIVATE_DIRECTORY
Only the Docker host executes Docker commands. Containers have no Docker socket.
Evidence is retained; cleanup validates recorded IDs and owner/project labels.
"""

import argparse
import asyncio
import json
import os
import re
import secrets
import shutil
import subprocess
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Literal
from uuid import uuid4

import httpx
from agent_inspection import CheckpointEvidence, CheckpointRow, ModelStoreEvidence
from local_services import free_port, private_json
from process_acceptance import (
    ROOT,
    ProcessConfig,
    acceptance,
    prepare_media,
    require_fresh_broker,
)
from pydantic import BaseModel, ConfigDict, Field, field_validator

SCRIPTS = ROOT / "scripts/integration"
SERVICES = frozenset(
    (
        "java-backend",
        "agent",
        "model-connector",
        "mysql",
        "kafka",
        "seaweedfs",
        "fixture",
        "integration-init",
    )
)


class DockerView(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)


class ContainerState(DockerView):
    running: bool = Field(alias="Running")
    oom_killed: bool = Field(alias="OOMKilled")
    exit_code: int = Field(alias="ExitCode")
    started_at: str = Field(alias="StartedAt")


class MountIdentity(DockerView):
    kind: str = Field(alias="Type")
    name: str | None = Field(default=None, alias="Name")
    source: str = Field(alias="Source")
    destination: str = Field(alias="Destination")
    writable: bool = Field(alias="RW")


def canonical_mounts(mounts: tuple[MountIdentity, ...]) -> tuple[MountIdentity, ...]:
    # Docker's mount array order is presentation; each destination identifies a mount.
    return tuple(sorted(mounts, key=lambda mount: mount.destination))


class NetworkAttachment(DockerView):
    identity: str = Field(alias="NetworkID")


class NetworkSettings(DockerView):
    networks: dict[str, NetworkAttachment] = Field(alias="Networks")


class ContainerConfig(DockerView):
    labels: dict[str, str] = Field(alias="Labels")
    user: str = Field(alias="User")


class ContainerInspection(DockerView):
    identity: str = Field(alias="Id")
    image: str = Field(alias="Image")
    config: ContainerConfig = Field(alias="Config")
    state: ContainerState = Field(alias="State")
    mounts: tuple[MountIdentity, ...] = Field(alias="Mounts")
    network_settings: NetworkSettings = Field(alias="NetworkSettings")

    @field_validator("mounts")
    @classmethod
    def normalize_mount_order(
        cls, value: tuple[MountIdentity, ...]
    ) -> tuple[MountIdentity, ...]:
        return canonical_mounts(value)


class ResourceInspection(DockerView):
    name: str = Field(alias="Name")
    identity: str | None = Field(default=None, alias="Id")
    labels: dict[str, str] = Field(alias="Labels")


@dataclass(frozen=True)
class OwnedContainer:
    service: str
    identity: str
    image: str
    user: str
    mounts: tuple[MountIdentity, ...]
    networks: dict[str, str]
    initial_state: ContainerState


@dataclass(frozen=True)
class OwnedResource:
    kind: Literal["volume", "network"]
    identity: str
    name: str


@dataclass(frozen=True)
class RestartEvidence:
    service: str
    container_id: str
    before: ContainerState
    after: ContainerState
    retained_image_mounts_networks: bool


@dataclass
class ComposeManifest:
    owner: str
    project: str
    docker_host: str
    host_config: ProcessConfig
    container_config: ProcessConfig
    containers: list[OwnedContainer] = field(default_factory=list)
    resources: list[OwnedResource] = field(default_factory=list)
    restarts: list[RestartEvidence] = field(default_factory=list)


@dataclass(frozen=True)
class ProjectInventory:
    containers: tuple[str, ...]
    volumes: tuple[str, ...]
    networks: tuple[str, ...]


@dataclass(frozen=True)
class CleanupEvidence:
    removed_container_ids: tuple[str, ...]
    removed_resources: tuple[OwnedResource, ...]
    # Docker inventory is opaque operator evidence; the harness does not interpret it.
    remaining_containers: str
    remaining_networks: str
    remaining_volumes: str


@dataclass(frozen=True)
class AcceptanceResult:
    status: Literal["PASS", "FAIL"]
    scope: str
    live_providers: bool
    cleanup_completed: bool


def manifest_document(manifest: ComposeManifest) -> dict:
    # Pydantic inspection records need explicit JSON conversion at this file boundary.
    return json.loads(
        json.dumps(asdict(manifest), default=lambda item: item.model_dump())
    )


class ComposeLifecycle:
    container_proof = True

    def __init__(self, directory: Path, manifest: ComposeManifest):
        self.directory = directory
        self.manifest = manifest
        self.next_liveness_check = 0.0
        self.creation_attempted = False
        self.discovery_started = False

    def save(self) -> None:
        private_json(
            self.directory / "compose-manifest.json", manifest_document(self.manifest)
        )

    def docker(self, *arguments: str, timeout: int = 60) -> str:
        return subprocess.run(
            ["docker", "--host", self.manifest.docker_host, *arguments],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        ).stdout.strip()

    def inspect(self, identity: str) -> ContainerInspection:
        return ContainerInspection.model_validate(
            json.loads(self.docker("inspect", identity))[0]
        )

    def owned(self, service: str) -> OwnedContainer:
        return next(
            item for item in self.manifest.containers if item.service == service
        )

    def require_owner(
        self, labels: dict[str, str], *, service: str | None = None
    ) -> None:
        if labels.get("lux.integration.owner") != self.manifest.owner:
            raise ValueError("Docker resource owner mismatch; refusing operation")
        if labels.get("com.docker.compose.project") != self.manifest.project:
            raise ValueError("Docker Compose project mismatch; refusing operation")
        if service is not None and labels.get("com.docker.compose.service") != service:
            raise ValueError("Docker Compose service mismatch; refusing operation")

    def network_ids(self, current: ContainerInspection) -> dict[str, str]:
        identities: dict[str, str] = {}
        for name, attachment in current.network_settings.networks.items():
            identity = attachment.identity
            if not identity:
                # Created containers have their network name before Docker assigns
                # the endpoint. Resolve the owned network without starting anything.
                resource = ResourceInspection.model_validate(
                    json.loads(self.docker("network", "inspect", name))[0]
                )
                self.require_owner(resource.labels)
                if resource.identity is None:
                    raise ValueError("Network has no stable recorded ID")
                identity = resource.identity
            identities[name] = identity
        return identities

    def require_container(self, recorded: OwnedContainer) -> ContainerInspection:
        current = self.inspect(recorded.identity)
        self.require_owner(current.config.labels, service=recorded.service)
        networks = self.network_ids(current)
        if (
            current.identity != recorded.identity
            or current.image != recorded.image
            or current.mounts != canonical_mounts(recorded.mounts)
            or networks != recorded.networks
        ):
            raise ValueError("Recorded container image/mount/network identity changed")
        if recorded.service in {
            "java-backend",
            "agent",
            "model-connector",
            "fixture",
        } and current.config.user not in {"10001", "10001:10001"}:
            raise ValueError("Business/fixture worker must retain runtime UID10001")
        return current

    def project_inventory(self) -> ProjectInventory:
        scope = f"label=com.docker.compose.project={self.manifest.project}"
        return ProjectInventory(
            tuple(
                self.docker("ps", "-aq", "--no-trunc", "--filter", scope).splitlines()
            ),
            tuple(
                self.docker("volume", "ls", "--quiet", "--filter", scope).splitlines()
            ),
            tuple(
                self.docker(
                    "network", "ls", "--quiet", "--no-trunc", "--filter", scope
                ).splitlines()
            ),
        )

    def require_fresh_project(self) -> None:
        inventory = self.project_inventory()
        if inventory.containers or inventory.volumes or inventory.networks:
            raise ValueError(
                "Generated Compose project already contains Docker resources; refusing create"
            )

    def record_resources(self) -> None:
        self.discovery_started = True
        inventory = self.project_inventory()
        known = {item.identity for item in self.manifest.containers}
        mounted_resources: list[tuple[Literal["volume", "network"], str]] = []
        for identity in inventory.containers:
            current = self.inspect(identity)
            service = current.config.labels.get("com.docker.compose.service", "")
            self.require_owner(current.config.labels, service=service)
            if service not in SERVICES:
                raise ValueError("Unexpected service in exclusive integration project")
            networks = self.network_ids(current)
            mounted_resources.extend(
                ("volume", mount.name)
                for mount in current.mounts
                if mount.kind == "volume" and mount.name is not None
            )
            mounted_resources.extend(
                ("network", identity) for identity in networks.values()
            )
            if current.identity not in known:
                self.manifest.containers.append(
                    OwnedContainer(
                        service,
                        current.identity,
                        current.image,
                        current.config.user,
                        current.mounts,
                        networks,
                        current.state,
                    )
                )
                known.add(current.identity)
                self.save()
        recorded_resources = {
            (item.kind, item.identity) for item in self.manifest.resources
        }
        # Compose can create these before its first container. Discover the exact
        # project-labeled scope independently of mounts to retain partial-create ownership.
        resources = (
            [("volume", identity) for identity in inventory.volumes]
            + [("network", identity) for identity in inventory.networks]
            + mounted_resources
        )
        for kind, identity in resources:
            resource = ResourceInspection.model_validate(
                json.loads(self.docker(kind, "inspect", identity))[0]
            )
            self.require_owner(resource.labels)
            if (kind, identity) not in recorded_resources:
                self.manifest.resources.append(
                    OwnedResource(kind, identity, resource.name)
                )
                recorded_resources.add((kind, identity))
                self.save()

    def healthy_http(self, service: str, url: str, seconds: int = 120) -> None:
        deadline = time.monotonic() + seconds
        with httpx.Client(trust_env=False, timeout=3) as http:
            while time.monotonic() < deadline:
                current = self.require_container(self.owned(service))
                if current.state.oom_killed or not current.state.running:
                    raise RuntimeError(
                        f"{service} exited or was OOM-killed; inspect private logs"
                    )
                try:
                    if http.get(url).status_code == 200:
                        return
                except httpx.RequestError:
                    pass
                time.sleep(0.5)
        raise TimeoutError(f"{service} readiness timed out")

    def check_liveness(self, request: httpx.Request) -> None:
        """Bound host assertion requests with all-container liveness, including fixture."""
        now = time.monotonic()
        if now < self.next_liveness_check:
            return
        running = [
            item
            for item in self.manifest.containers
            if item.service != "integration-init"
        ]
        inspected = [
            ContainerInspection.model_validate(item)
            for item in json.loads(
                self.docker("inspect", *(item.identity for item in running))
            )
        ]
        by_id = {item.identity: item for item in inspected}
        for recorded in running:
            current = by_id[recorded.identity]
            self.require_owner(current.config.labels, service=recorded.service)
            if current.state.oom_killed or not current.state.running:
                raise RuntimeError(
                    f"{recorded.service} exited or was OOM-killed; stopping acceptance"
                )
        self.next_liveness_check = now + 2

    def restart(self, service: str, health_url: str) -> None:
        recorded = self.owned(service)
        before = self.require_container(recorded)
        if not before.state.running or before.state.oom_killed:
            raise RuntimeError("Cannot restart an unexpectedly stopped container")
        try:
            self.docker("restart", "--time", "15", recorded.identity, timeout=45)
        except subprocess.TimeoutExpired:
            # Inspect the original ID; never recreate or repeat an uncertain restart.
            after_timeout = self.require_container(recorded)
            if after_timeout.state.started_at == before.state.started_at:
                raise RuntimeError(
                    "Restart timed out without a changed start timestamp"
                ) from None
        self.healthy_http(service, health_url)
        after = self.require_container(recorded)
        if after.state.started_at == before.state.started_at:
            raise AssertionError(
                "Restart did not change the original container start timestamp"
            )
        self.manifest.restarts.append(
            RestartEvidence(service, recorded.identity, before.state, after.state, True)
        )
        self.save()

    def restart_agent(self) -> None:
        self.restart("agent", self.manifest.host_config.agent_url + "/health")

    def restart_connector(self) -> None:
        self.restart(
            "model-connector", self.manifest.host_config.connector_url + "/health"
        )

    def restart_java(self) -> None:
        self.restart(
            "java-backend",
            self.manifest.host_config.backend_url + "/actuator/health/liveness",
        )

    def execute(self, service: str, script: str, *arguments: str) -> str:
        recorded = self.owned(service)
        self.require_container(recorded)
        return self.docker(
            "exec",
            recorded.identity,
            "python",
            "/opt/lux-integration/" + script,
            *arguments,
        )

    def inspect_model(self, execution_id: str) -> ModelStoreEvidence:
        return ModelStoreEvidence(
            **json.loads(
                self.execute(
                    "agent",
                    "agent_inspection.py",
                    "model",
                    "--store",
                    "/var/lib/lux-agent/agent.db",
                    "--execution-id",
                    execution_id,
                )
            )
        )

    def inspect_checkpoints(self) -> CheckpointEvidence:
        data = json.loads(
            self.execute(
                "agent",
                "agent_inspection.py",
                "checkpoints",
                "--store",
                "/var/lib/lux-agent/agent.db",
            )
        )
        return CheckpointEvidence(tuple(CheckpointRow(**row) for row in data["rows"]))

    def check_database(self) -> str:
        return self.execute(
            "model-connector", "database_acceptance.py", "/opt/lux-config/process.json"
        )

    def capture_logs(self) -> None:
        for recorded in self.manifest.containers:
            try:
                current = self.require_container(recorded)
                private_json(
                    self.directory / (recorded.service + "-state.json"),
                    current.model_dump(),
                )
                logs = subprocess.run(
                    [
                        "docker",
                        "--host",
                        self.manifest.docker_host,
                        "logs",
                        "--timestamps",
                        recorded.identity,
                    ],
                    check=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=60,
                ).stdout
                target = self.directory / (recorded.service + ".log")
                target.touch(mode=0o600)
                target.write_text(logs)
            except (subprocess.SubprocessError, ValueError) as error:
                private_json(
                    self.directory / (recorded.service + "-capture-error.json"),
                    {"error": type(error).__name__},
                )

    @staticmethod
    def missing_resource(error: subprocess.CalledProcessError) -> bool:
        message = (error.stderr or "").lower()
        return any(
            marker in message
            for marker in (
                "no such object",
                "no such container",
                "no such volume",
                "no such network",
            )
        ) or ("network" in message and "not found" in message)

    def cleanup(self) -> None:
        # Fresh-project rejection must leave all pre-existing resources untouched.
        if self.creation_attempted or self.discovery_started:
            self.record_resources()
        # Validate the complete remaining scope before any removal. Already removed
        # IDs are skipped so a second cleanup after partial/successful removal is safe.
        remaining_containers: list[OwnedContainer] = []
        remaining_resources: list[OwnedResource] = []
        for recorded in self.manifest.containers:
            try:
                self.require_container(recorded)
            except subprocess.CalledProcessError as error:
                if self.missing_resource(error):
                    continue
                raise
            remaining_containers.append(recorded)
        for recorded in self.manifest.resources:
            try:
                current = ResourceInspection.model_validate(
                    json.loads(
                        self.docker(recorded.kind, "inspect", recorded.identity)
                    )[0]
                )
            except subprocess.CalledProcessError as error:
                if self.missing_resource(error):
                    continue
                raise
            self.require_owner(current.labels)
            if current.name != recorded.name or (
                recorded.kind == "network" and current.identity != recorded.identity
            ):
                raise ValueError(
                    "Recorded Docker resource identity changed; refusing cleanup"
                )
            remaining_resources.append(recorded)
        for recorded in reversed(remaining_containers):
            self.docker("rm", "--force", recorded.identity)
        for kind in ("network", "volume"):
            for recorded in remaining_resources:
                if recorded.kind == kind:
                    self.docker(kind, "rm", recorded.identity)
        private_json(
            self.directory / "cleanup-evidence.json",
            asdict(
                CleanupEvidence(
                    tuple(item.identity for item in self.manifest.containers),
                    tuple(self.manifest.resources),
                    self.docker("ps", "-a", "--format", "{{json .}}"),
                    self.docker("network", "ls", "--format", "{{json .}}"),
                    self.docker("volume", "ls", "--format", "{{json .}}"),
                )
            ),
        )


def initialize(source: Path, destination: Path) -> None:
    """Trusted one-shot root initializer; no application runtime needs elevated UID."""
    import pymysql

    config = ProcessConfig(
        **json.loads((source / "container-process.json").read_text())
    )
    names = (config.backend_database, config.connector_database)
    if any(
        not re.fullmatch(r"lux_process_(backend|connector)_[0-9a-f]{12}", name)
        for name in names
    ):
        raise ValueError("Initializer refuses schemas outside disposable UUID scope")
    destination.mkdir(exist_ok=True)
    os.chown(destination, 10001, 10001)
    destination.chmod(0o700)
    for name in ("image.png", "video.mp4", "reference.png", "invalid-reference.png"):
        output = destination / name
        shutil.copyfile(source / name, output)
        os.chown(output, 10001, 10001)
        output.chmod(0o600)
    target = destination / "process.json"
    shutil.copyfile(source / "container-process.json", target)
    os.chown(target, 10001, 10001)
    target.chmod(0o600)
    asyncio.run(require_fresh_broker(config.kafka))
    with (
        pymysql.connect(
            host=config.mysql_host,
            port=config.mysql_port,
            user="root",
            password=config.mysql_password,
            autocommit=True,
        ) as connection,
        connection.cursor() as cursor,
    ):
        for name in names:
            cursor.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4")
    print(
        "Initialized only the two fresh UUID schemas and private fixture configuration"
    )


def run(directory: Path) -> None:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    if any(directory.iterdir()):
        raise ValueError("Acceptance requires a fresh empty private evidence directory")
    identity = uuid4().hex[:12]
    ports = [free_port() for _ in range(7)]
    host = ProcessConfig(
        str(directory),
        *(f"http://127.0.0.1:{port}" for port in ports[:4]),
        f"127.0.0.1:{ports[5]}",
        "127.0.0.1",
        ports[4],
        secrets.token_urlsafe(24),
        "lux_process_backend_" + identity,
        "lux_process_connector_" + identity,
        f"http://127.0.0.1:{ports[6]}",
        "lux-integration",
        secrets.token_urlsafe(32),
        secrets.token_urlsafe(32),
    )
    container = ProcessConfig(
        **{
            **asdict(host),
            "directory": "/opt/lux-config",
            "backend_url": "http://java-backend:8080",
            "agent_url": "http://agent:8000",
            "connector_url": "http://model-connector:8001",
            "fixture_url": "http://fixture:8003",
            "mysql_host": "mysql",
            "mysql_port": 3306,
            "kafka": "kafka:9092",
            "s3_url": "http://seaweedfs:8333",
        }
    )
    docker_host = os.environ.get("DOCKER_HOST")
    if not docker_host:
        docker_host = subprocess.run(
            ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    lifecycle = ComposeLifecycle(
        directory,
        ComposeManifest(identity, "lux-it-" + identity, docker_host, host, container),
    )
    lifecycle.save()
    private_json(directory / "host-process.json", asdict(host))
    private_json(directory / "container-process.json", asdict(container))
    prepare_media(directory)
    environment = {
        "LUX_INTEGRATION_OWNER": identity,
        "LUX_INTEGRATION_SOURCE": str(directory),
        "LUX_INTEGRATION_SCRIPTS": str(SCRIPTS),
        "LUX_MYSQL_PASSWORD": host.mysql_password,
        "LUX_DB_PASSWORD": host.mysql_password,
        "LUX_BACKEND_DATABASE": host.backend_database,
        "LUX_CONNECTOR_DATABASE": host.connector_database,
        "LUX_OPERATOR_TOKEN": host.operator_token,
        "LUX_S3_ACCESS_KEY": host.s3_access_key,
        "LUX_S3_SECRET_KEY": host.s3_secret_key,
        "LUX_BACKEND_PORT": str(ports[0]),
        "LUX_AGENT_PORT": str(ports[1]),
        "LUX_CONNECTOR_PORT": str(ports[2]),
        "LUX_FIXTURE_PORT": str(ports[3]),
        "LUX_MYSQL_PORT": str(ports[4]),
        "LUX_KAFKA_PORT": str(ports[5]),
        "LUX_S3_PORT": str(ports[6]),
    }
    envfile = directory / "compose.env"
    envfile.touch(mode=0o600)
    envfile.write_text(
        "".join(f"{name}={value}\n" for name, value in environment.items())
    )
    arguments = (
        "compose",
        "--project-name",
        lifecycle.manifest.project,
        "--env-file",
        str(envfile),
        "-f",
        str(ROOT / "deploy/local/compose.yml"),
        "-f",
        str(ROOT / "deploy/local/compose.integration.yml"),
    )
    # Reject inherited runtime secrets/endpoints that could override this isolated env file.
    original_environment = os.environ.copy()
    for name in tuple(os.environ):
        if name.startswith(("LUX_", "COMPOSE_")):
            del os.environ[name]
    os.environ["COMPOSE_PARALLEL_LIMIT"] = "1"
    outcome = "FAIL"
    try:
        lifecycle.docker(*arguments, "config", "--quiet")
        lifecycle.require_fresh_project()
        lifecycle.creation_attempted = True
        try:
            lifecycle.docker(
                *arguments, "create", "--no-build", "--pull", "never", timeout=120
            )
        finally:
            lifecycle.record_resources()
        if {item.service for item in lifecycle.manifest.containers} != SERVICES:
            raise ValueError(
                "Compose did not create exactly the integration service set"
            )
        for service in ("mysql", "kafka", "seaweedfs", "integration-init"):
            recorded = lifecycle.owned(service)
            lifecycle.require_container(recorded)
            lifecycle.docker("start", recorded.identity)
            if service == "integration-init":
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline:
                    state = lifecycle.require_container(recorded).state
                    if not state.running:
                        if state.exit_code or state.oom_killed:
                            raise RuntimeError("Integration initialization failed")
                        break
                    time.sleep(0.5)
                else:
                    raise TimeoutError("Integration initialization timed out")
            else:
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline:
                    status = lifecycle.docker(
                        "inspect",
                        "--format",
                        "{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}",
                        recorded.identity,
                    )
                    state = lifecycle.require_container(recorded).state
                    if state.oom_killed or not state.running or status == "unhealthy":
                        raise RuntimeError(f"{service} did not stay healthy")
                    if status == "healthy":
                        break
                    time.sleep(0.5)
                else:
                    raise TimeoutError(f"{service} health timed out")
        for service, url in (
            ("fixture", host.fixture_url + "/health"),
            ("java-backend", host.backend_url + "/actuator/health/liveness"),
            ("model-connector", host.connector_url + "/health"),
            ("agent", host.agent_url + "/health"),
        ):
            recorded = lifecycle.owned(service)
            lifecycle.require_container(recorded)
            if not lifecycle.require_container(recorded).state.running:
                lifecycle.docker("start", recorded.identity)
            lifecycle.healthy_http(service, url)
            if service == "java-backend":
                # The Connector image has the typed database models; it is started briefly
                # only after Java has initialized its fresh schema, before Agent admission.
                connector = lifecycle.owned("model-connector")
                lifecycle.require_container(connector)
                lifecycle.docker("start", connector.identity)
                lifecycle.healthy_http(
                    "model-connector", host.connector_url + "/health"
                )
                lifecycle.execute(
                    "model-connector",
                    "database_acceptance.py",
                    "/opt/lux-config/process.json",
                    "seed-other-owner",
                )
        with httpx.Client(
            trust_env=False,
            timeout=10,
            event_hooks={"request": [lifecycle.check_liveness]},
        ) as http:
            acceptance(http, host, lifecycle)
        outcome = "PASS"
    finally:
        cleanup_completed = False
        try:
            lifecycle.capture_logs()
            lifecycle.cleanup()
            cleanup_completed = True
        finally:
            private_json(
                directory / "result.json",
                asdict(
                    AcceptanceResult(
                        "PASS" if outcome == "PASS" and cleanup_completed else "FAIL",
                        "deterministic-compose-fixture",
                        False,
                        cleanup_completed,
                    )
                ),
            )
            os.environ.clear()
            os.environ.update(original_environment)
    print(f"PASS: full Compose acceptance; private evidence: {directory}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "initialize"))
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--destination", type=Path)
    options = parser.parse_args()
    if options.mode == "run":
        if options.directory is None:
            parser.error("run requires --directory")
        run(options.directory.resolve())
    else:
        if options.source is None or options.destination is None:
            parser.error("initialize requires --source and --destination")
        initialize(options.source, options.destination)
