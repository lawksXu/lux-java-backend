"""Launch and remove isolated local MySQL/Kafka/S3 acceptance services.

Run with Python 3.11+: python scripts/integration/local_services.py start DIR
Then run the component tests with DIR/connector-test.json as their private config.
Stop only this launcher's resources: python scripts/integration/local_services.py stop DIR
Docker Desktop's raw daemon socket can be selected with DOCKER_HOST.
Provider calls and shared infrastructure are never used by this launcher.
"""

import argparse
import json
import os
import secrets
import socket
import subprocess
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from uuid import uuid4


@dataclass(frozen=True)
class Container:
    name: str
    container_id: str
    image: str
    memory_limit: str | None = None


@dataclass
class Services:
    owner: str
    docker_host: str | None
    mysql_port: int
    kafka_port: int
    s3_port: int
    mysql_password: str
    s3_access_key: str
    s3_secret_key: str
    containers: list[Container] = field(default_factory=list)


@dataclass(frozen=True)
class ConnectorTestConfig:
    host: str
    port: int
    user: str
    password: str
    kafka: str


def docker(services: Services, *arguments: str) -> str:
    command = ["docker"]
    if services.docker_host:
        command += ["--host", services.docker_host]
    return subprocess.run(
        [*command, *arguments], check=True, capture_output=True, text=True, timeout=60
    ).stdout.strip()


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def private_json(path: Path, value: object) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def create(
    directory: Path,
    services: Services,
    suffix: str,
    image: str,
    port: int,
    container_port: int,
    environment: tuple[str, ...],
    arguments: tuple[str, ...] = (),
    *,
    memory_limit: str = "512m",
) -> None:
    name = f"lux-it-{services.owner}-{suffix}"
    command = [
        "create",
        "--name",
        name,
        "--label",
        f"lux.integration.owner={services.owner}",
        "--memory",
        memory_limit,
        "--publish",
        f"127.0.0.1:{port}:{container_port}",
    ]
    for variable in environment:
        command += ["--env", variable]
    container_id = docker(services, *command, image, *arguments)
    services.containers.append(Container(name, container_id, image, memory_limit))
    private_json(directory / "services.json", asdict(services))
    # Persist identity before starting: an uncertain start is reconciled, never recreated.
    docker(services, "start", container_id)


def start(directory: Path) -> None:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    if (directory / "services.json").exists():
        raise ValueError(
            "Manifest already exists; inspect or stop its owned resources first"
        )
    services = Services(
        uuid4().hex[:12],
        os.environ.get("DOCKER_HOST"),
        free_port(),
        free_port(),
        free_port(),
        secrets.token_urlsafe(24),
        "lux-integration",
        secrets.token_urlsafe(32),
    )
    private_json(directory / "services.json", asdict(services))
    create(
        directory,
        services,
        "mysql",
        "mysql:8.4",
        services.mysql_port,
        3306,
        (
            f"MYSQL_ROOT_PASSWORD={services.mysql_password}",
            "MYSQL_DATABASE=lux_backend_it",
        ),
        ("--default-time-zone=+00:00", "--max-allowed-packet=536870912"),
        memory_limit="768m",
    )
    port = services.kafka_port
    create(
        directory,
        services,
        "kafka",
        "apache/kafka-native:4.0.2",
        port,
        9092,
        (
            "KAFKA_NODE_ID=1",
            "KAFKA_PROCESS_ROLES=broker,controller",
            "KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093",
            f"KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:{port}",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
            "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER",
            "KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093",
            "KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT",
            "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1",
            "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1",
            "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1",
            "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0",
            "KAFKA_MESSAGE_MAX_BYTES=33554432",
            "KAFKA_REPLICA_FETCH_MAX_BYTES=33554432",
            "CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk",
        ),
    )
    create(
        directory,
        services,
        "s3",
        "chrislusf/seaweedfs:4.47",
        services.s3_port,
        8333,
        (
            f"AWS_ACCESS_KEY_ID={services.s3_access_key}",
            f"AWS_SECRET_ACCESS_KEY={services.s3_secret_key}",
            "S3_BUCKET=lux-assets",
        ),
        ("mini", "-dir=/data", "-ip=127.0.0.1", "-ip.bind=0.0.0.0", "-s3.port=8333"),
        memory_limit="384m",
    )
    config = ConnectorTestConfig(
        "127.0.0.1",
        services.mysql_port,
        "root",
        services.mysql_password,
        f"127.0.0.1:{services.kafka_port}",
    )
    private_json(directory / "connector-test.json", asdict(config))
    deadline = time.monotonic() + 60
    for port in (services.mysql_port, services.kafka_port, services.s3_port):
        while True:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=1):
                    break
            except OSError:
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"Local service on {port} did not listen within 60 seconds"
                    )
                time.sleep(0.2)
    mysql_container = services.containers[0]
    deadline = time.monotonic() + 60
    while True:
        try:
            capacity = docker(
                services,
                "exec",
                "--env",
                f"MYSQL_PWD={services.mysql_password}",
                mysql_container.container_id,
                "mysql",
                "--user=root",
                "--batch",
                "--skip-column-names",
                "--execute",
                "SELECT @@max_allowed_packet",
            )
            if capacity == "536870912":
                break
        except subprocess.CalledProcessError:
            pass
        if time.monotonic() >= deadline:
            raise TimeoutError(
                "MySQL did not accept authenticated SQL with required packet capacity within60seconds"
            )
        time.sleep(0.2)
    print(
        f"Services listening and MySQL SQL ready; run component acceptance before claiming readiness. Private manifest: {directory / 'services.json'}"
    )
    print(f"LUX_CONNECTOR_TEST_CONFIG={directory / 'connector-test.json'}")


def stop(directory: Path) -> None:
    # Raw JSON is decoded only at this local manifest ingress boundary.
    document = json.loads((directory / "services.json").read_text())
    containers = [Container(**entry) for entry in document.pop("containers")]
    services = Services(**document, containers=containers)
    for container in containers:
        try:
            label = docker(
                services,
                "inspect",
                "--format",
                '{{index .Config.Labels "lux.integration.owner"}}',
                container.container_id,
            )
        except subprocess.CalledProcessError as error:
            if "No such object" in error.stderr:
                continue
            raise
        if label != services.owner:
            raise ValueError("Container ownership changed; refusing cleanup")
        docker(services, "rm", "--force", "--volumes", container.container_id)
    print(
        "Removed only manifest-owned containers and their anonymous volumes; retained evidence files."
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "stop"))
    parser.add_argument("directory", type=Path)
    parsed = parser.parse_args()
    (start if parsed.action == "start" else stop)(parsed.directory.resolve())
