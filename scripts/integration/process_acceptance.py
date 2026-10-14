"""Run real Java/Agent/Connector processes over local MySQL/Kafka/S3.

From the Agent virtual environment:
  python scripts/integration/process_acceptance.py run --services DIR --java-jar target/JAR
DIR is a private local_services.py manifest directory. Maven must build JAR first.
Only OpenAI/BytePlus HTTP is redirected to a deterministic loopback provider fixture.
All process state/logs/config remain private in DIR/process-<uuid>; services are retained.
"""

import argparse
import asyncio
import hashlib
import json
import os
import secrets
import signal
import subprocess
import time
from collections.abc import AsyncIterator, Callable
from contextlib import ExitStack, asynccontextmanager
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal, Protocol
from uuid import uuid4

import httpx
import uvicorn
from agent_inspection import (
    CheckpointEvidence,
    CheckpointRetentionEvidence,
    ModelStoreEvidence,
    model_store,
)
from local_services import Container, Services, free_port, private_json
from pydantic import BaseModel, ConfigDict

ROOT = Path(__file__).resolve().parents[2]
AGENT = ROOT.parent / "lux-python-agent"
CONNECTOR = ROOT.parent / "lux-python-model-connector"
USER = "00000000-0000-0000-0000-000000000001"
OTHER = "00000000-0000-0000-0000-000000000002"


@dataclass(frozen=True)
class ProcessConfig:
    directory: str
    backend_url: str
    agent_url: str
    connector_url: str
    fixture_url: str
    kafka: str
    mysql_host: str
    mysql_port: int
    mysql_password: str
    backend_database: str
    connector_database: str
    s3_url: str
    s3_access_key: str
    s3_secret_key: str
    operator_token: str


@dataclass
class ProcessIdentity:
    name: str
    pid: int
    exit_code: int | None = None


class AcceptanceLifecycle(Protocol):
    container_proof: bool

    def restart_agent(self) -> None: ...
    def restart_connector(self) -> None: ...
    def restart_java(self) -> None: ...
    def inspect_model(self, execution_id: str) -> ModelStoreEvidence: ...
    def inspect_checkpoints(self) -> CheckpointEvidence: ...
    def check_database(self) -> str: ...


@dataclass
class ProcessLifecycle:
    http: httpx.Client
    config: ProcessConfig
    agent_process: subprocess.Popen
    connector_process: subprocess.Popen
    launch: Callable[[str, list[str], dict[str, str], Path], subprocess.Popen]
    agent_command: list[str]
    agent_environment: dict[str, str]
    connector_environment: dict[str, str]
    path: Path
    container_proof: bool = False

    def restart_agent(self) -> None:
        terminate(self.agent_process)
        self.agent_process = self.launch(
            "agent-restarted",
            [*self.agent_command, "agent", "--config", str(self.path)],
            self.agent_environment,
            AGENT,
        )
        await_http(self.http, self.config.agent_url + "/health", [self.agent_process])

    def restart_connector(self) -> None:
        terminate(self.connector_process)
        self.connector_process = self.launch(
            "connector-restarted",
            [
                str(CONNECTOR / ".venv/bin/python"),
                str(Path(__file__).resolve()),
                "connector",
                "--config",
                str(self.path),
            ],
            self.connector_environment,
            CONNECTOR,
        )
        await_http(
            self.http, self.config.connector_url + "/health", [self.connector_process]
        )

    def inspect_model(self, execution_id: str) -> ModelStoreEvidence:
        return model_store(Path(self.config.directory) / "agent.db", execution_id)

    def check_database(self) -> str:
        return subprocess.run(
            [
                str(CONNECTOR / ".venv/bin/python"),
                str(ROOT / "scripts/integration/database_acceptance.py"),
                str(self.path),
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout

    def inspect_checkpoints(self) -> CheckpointEvidence:
        raise RuntimeError(
            "Container checkpoint proof is not enabled for host processes"
        )

    def restart_java(self) -> None:
        raise RuntimeError(
            "Container Java restart proof is not enabled for host processes"
        )


class View(BaseModel):
    model_config = ConfigDict(extra="ignore")


class SessionView(View):
    id: str


class AssetView(View):
    id: str
    user_id: str
    task_id: str | None = None
    media_type: str
    size_bytes: int
    sha256: str


class TaskView(View):
    id: str
    session_id: str
    status: str
    plan_id: str | None
    assets: tuple[AssetView, ...]


class RedirectAsync(httpx.AsyncBaseTransport):
    """Allow only the named production provider host, mapped to fixture HTTP."""

    def __init__(self, host: str, fixture_url: str):
        self.host = host
        self.fixture = httpx.URL(fixture_url)
        self.transport = httpx.AsyncHTTPTransport()

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        if request.url.host != self.host:
            raise RuntimeError("Provider test transport refuses non-fixture targets")
        request.url = request.url.copy_with(
            scheme=self.fixture.scheme, host=self.fixture.host, port=self.fixture.port
        )
        return await self.transport.handle_async_request(request)

    async def aclose(self) -> None:
        await self.transport.aclose()


class RedirectSync(httpx.BaseTransport):
    def __init__(self, host: str, fixture_url: str):
        self.host = host
        self.fixture = httpx.URL(fixture_url)
        self.transport = httpx.HTTPTransport()

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        if request.url.host != self.host:
            raise RuntimeError("Provider test transport refuses non-fixture targets")
        request.url = request.url.copy_with(
            scheme=self.fixture.scheme, host=self.fixture.host, port=self.fixture.port
        )
        return self.transport.handle_request(request)

    def close(self) -> None:
        self.transport.close()


def agent_app(config: ProcessConfig):
    from lux_agent.execution import LiveOutputSlot
    from lux_agent.main import create_app
    from lux_agent.openai_model import OpenAIModelExecutor

    class DeferredModel:
        live_slots = (
            LiveOutputSlot(key="answer", kind="MESSAGE"),
            LiveOutputSlot(key="reasoning", kind="REASONING_SUMMARY"),
        )
        adapter: OpenAIModelExecutor | None = None
        http = httpx.AsyncClient(
            transport=RedirectAsync("api.openai.com", config.fixture_url), timeout=20
        )
        sync_http = httpx.Client(
            transport=RedirectSync("api.openai.com", config.fixture_url), timeout=20
        )

        def resolve(self) -> OpenAIModelExecutor:
            if self.adapter is None:
                runtime = app.state.runtime
                self.adapter = OpenAIModelExecutor(
                    runtime.store,
                    api_key="offline-fixture-key",
                    account_ref="local-openai",
                    http_client=self.http,
                    sync_http_client=self.sync_http,
                    media_observer=runtime.media_observer,
                )
            return self.adapter

        async def stream(self, request) -> AsyncIterator:
            async for result in self.resolve().stream(request):
                yield result

        async def reconcile(self, request):
            return await self.resolve().reconcile(request)

    model = DeferredModel()
    app = create_app(executor=model, model_reconciler=model)
    original = app.router.lifespan_context

    @asynccontextmanager
    async def lifespan(application):
        try:
            async with original(application):
                yield
        finally:
            await model.http.aclose()
            model.sync_http.close()

    app.router.lifespan_context = lifespan
    return app


def connector_app(config: ProcessConfig):
    from lux_model_connector.byteplus import BytePlusClient
    from lux_model_connector.main import ServiceSettings, create_app

    settings = ServiceSettings.from_environment()
    provider_http = httpx.AsyncClient(
        transport=RedirectAsync("ark.ap-southeast.bytepluses.com", config.fixture_url),
        timeout=20,
    )
    app = create_app(
        settings, provider_client=BytePlusClient(settings.byteplus, provider_http)
    )
    original = app.router.lifespan_context

    @asynccontextmanager
    async def lifespan(application):
        try:
            async with original(application):
                yield
        finally:
            await provider_http.aclose()

    app.router.lifespan_context = lifespan
    return app


def worker(mode: str, path: Path, bind_address: str = "127.0.0.1") -> None:
    config = ProcessConfig(**json.loads(path.read_text()))
    if mode == "fixture":
        from provider_fixture import create_app

        app, url = (
            create_app(Path(config.directory), config.fixture_url),
            config.fixture_url,
        )
    elif mode == "agent":
        app, url = agent_app(config), config.agent_url
    else:
        app, url = connector_app(config), config.connector_url
    uvicorn.run(app, host=bind_address, port=httpx.URL(url).port, log_level="info")


def request(http: httpx.Client, method: str, url: str, **kwargs) -> httpx.Response:
    response = http.request(method, url, **kwargs)
    response.raise_for_status()
    return response


def await_http(http: httpx.Client, url: str, processes: list[subprocess.Popen]) -> None:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if any(process.poll() is not None for process in processes):
            raise RuntimeError("A process exited during startup; inspect private logs")
        try:
            if http.get(url).status_code == 200:
                return
        except httpx.RequestError:
            pass
        time.sleep(0.25)
    raise TimeoutError("Process did not become available within90seconds")


def prepare_media(directory: Path) -> None:
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "color=c=blue:s=2048x2048",
            "-frames:v",
            "1",
            str(directory / "image.png"),
        ],
        check=True,
    )
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=s=1280x720:r=24:d=5",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            str(directory / "video.mp4"),
        ],
        check=True,
    )
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "color=c=red:s=750x300",
            "-frames:v",
            "1",
            str(directory / "reference.png"),
        ],
        check=True,
    )
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc=s=751x300",
            "-frames:v",
            "1",
            str(directory / "invalid-reference.png"),
        ],
        check=True,
    )


async def require_fresh_broker(bootstrap: str) -> None:
    from aiokafka.admin import AIOKafkaAdminClient

    admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap)
    await admin.start()
    try:
        topics = await admin.list_topics()
        if any(topic.startswith("lux.") for topic in topics):
            raise ValueError(
                "Process acceptance needs a fresh broker without previous Lux topic history; launch new isolated services"
            )
    finally:
        await admin.close()


def run(services_directory: Path, java_jar: Path) -> None:
    services_document = json.loads((services_directory / "services.json").read_text())
    services = Services(
        **{
            **services_document,
            "containers": [
                Container(**entry) for entry in services_document["containers"]
            ],
        }
    )
    asyncio.run(require_fresh_broker(f"127.0.0.1:{services.kafka_port}"))
    identity = uuid4().hex[:12]
    directory = services_directory / ("process-" + identity)
    directory.mkdir(mode=0o700)
    config = ProcessConfig(
        str(directory),
        *(f"http://127.0.0.1:{free_port()}" for _ in range(4)),
        f"127.0.0.1:{services.kafka_port}",
        "127.0.0.1",
        services.mysql_port,
        services.mysql_password,
        "lux_process_backend_" + identity,
        "lux_process_connector_" + identity,
        f"http://127.0.0.1:{services.s3_port}",
        services.s3_access_key,
        services.s3_secret_key,
        secrets.token_urlsafe(32),
    )
    path = directory / "process.json"
    private_json(path, config.__dict__)
    prepare_media(directory)
    # Controlled UUID names and private password are passed through stdin, never shell interpolation.
    sql = """import json,sys,pymysql,time
c=json.load(sys.stdin)
deadline=time.monotonic()+60
while True:
 try:
  x=pymysql.connect(host=c['mysql_host'],port=c['mysql_port'],user='root',password=c['mysql_password'],autocommit=True,connect_timeout=2,read_timeout=2)
  with x.cursor() as q:q.execute('SELECT 1')
  break
 except pymysql.MySQLError:
  if time.monotonic()>=deadline:raise
  time.sleep(0.2)
with x.cursor() as q:
 for name in (c['backend_database'],c['connector_database']):q.execute('CREATE DATABASE `'+name+'` CHARACTER SET utf8mb4')
x.close()
"""
    subprocess.run(
        [str(CONNECTOR / ".venv/bin/python"), "-c", sql],
        input=json.dumps(config.__dict__),
        text=True,
        check=True,
    )
    # Inherited application/cloud settings must not override isolated service endpoints.
    runtime_environment = {
        name: value
        for name, value in os.environ.items()
        if name in {"PATH", "HOME", "TMPDIR", "JAVA_HOME", "LANG", "LC_ALL", "TZ"}
    }
    environment = {
        **runtime_environment,
        "NO_PROXY": "127.0.0.1,localhost",
        "no_proxy": "127.0.0.1,localhost",
        "LUX_KAFKA_BOOTSTRAP_SERVERS": config.kafka,
        "LUX_BACKEND_URL": config.backend_url,
    }
    processes: list[subprocess.Popen] = []
    process_ids: list[ProcessIdentity] = []
    logs = ExitStack()

    def launch(
        name: str, command: list[str], extra: dict[str, str], cwd: Path
    ) -> subprocess.Popen:
        log = logs.enter_context((directory / (name + ".log")).open("w"))
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env={**environment, **extra},
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        processes.append(process)
        process_ids.append(ProcessIdentity(name, process.pid))
        private_json(
            directory / "process-pids.json", [asdict(record) for record in process_ids]
        )
        return process

    script = str(Path(__file__).resolve())
    agent_command = [str(AGENT / ".venv/bin/python"), script]
    agent_environment = {
        "LUX_AGENT_STORE": str(directory / "agent.db"),
        "LUX_KAFKA_GROUP_ID": "lux-process-agent-" + identity,
        "LUX_AGENT_MEDIA_OBSERVATION_ENABLED": "true",
        "LUX_AGENT_RECONCILIATION_ENABLED": "true",
        "LUX_AGENT_RECONCILIATION_TOKEN": config.operator_token,
        "LUX_AGENT_RECONCILIATION_OPERATOR": "local-fixture-operator",
        "LUX_AGENT_RECONCILIATION_ACCOUNTS": "local-openai",
    }
    connector_environment = {
        "LUX_CONNECTOR_MYSQL_DSN": f"mysql://root:{config.mysql_password}@127.0.0.1:{config.mysql_port}/{config.connector_database}",
        "LUX_CONNECTOR_KAFKA_GROUP_ID": "lux-process-connector-" + identity,
        "LUX_BYTEPLUS_API_KEY": "offline-fixture-key",
        "LUX_BYTEPLUS_ACCOUNT_REF": "local-byteplus",
        "LUX_CONNECTOR_PROVIDER_EXECUTION_ENABLED": "true",
        "LUX_CONNECTOR_PROVIDER_POLL_SECONDS": "0.1",
        "LUX_CONNECTOR_S3_BUCKET": "lux-assets",
        "LUX_CONNECTOR_S3_ENDPOINT_URL": config.s3_url,
        "LUX_CONNECTOR_S3_ACCESS_KEY_ID": config.s3_access_key,
        "LUX_CONNECTOR_S3_SECRET_ACCESS_KEY": config.s3_secret_key,
        "LUX_CONNECTOR_RESULT_BASE_URL": config.connector_url,
        "LUX_CONNECTOR_RECONCILIATION_ENABLED": "true",
        "LUX_CONNECTOR_RECONCILIATION_TOKEN": config.operator_token,
        "LUX_CONNECTOR_RECONCILIATION_OPERATOR": "local-fixture-operator",
        "LUX_CONNECTOR_RECONCILIATION_ACCOUNTS": "local-byteplus",
    }
    try:
        with httpx.Client(trust_env=False, timeout=10) as http:
            launch(
                "fixture", [*agent_command, "fixture", "--config", str(path)], {}, AGENT
            )
            await_http(http, config.fixture_url + "/health", processes)
            launch(
                "java",
                [
                    "java",
                    "-jar",
                    str(java_jar.resolve()),
                    f"--server.port={httpx.URL(config.backend_url).port}",
                ],
                {
                    "LUX_DB_URL": f"jdbc:mysql://127.0.0.1:{config.mysql_port}/{config.backend_database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                    "LUX_BIND_ADDRESS": "127.0.0.1",
                    "LUX_S3_BUCKET": "lux-assets",
                    "LUX_DB_USER": "root",
                    "LUX_DB_PASSWORD": config.mysql_password,
                    "LUX_BYTEPLUS_GENERATION_ENABLED": "true",
                    "LUX_S3_ENDPOINT": config.s3_url,
                    "LUX_S3_ACCESS_KEY": config.s3_access_key,
                    "LUX_S3_SECRET_KEY": config.s3_secret_key,
                    "LUX_AGENT_BASE_URL": config.agent_url,
                    "LUX_STORAGE_ROOT": str(directory / "assets"),
                    "LUX_STORAGE_STAGING_ROOT": str(directory / "staging"),
                    "SPRING_KAFKA_CONSUMER_GROUP_ID": "lux-process-java-" + identity,
                },
                ROOT,
            )
            await_http(
                http, config.backend_url + "/actuator/health/liveness", processes
            )
            subprocess.run(
                [
                    str(CONNECTOR / ".venv/bin/python"),
                    str(ROOT / "scripts/integration/database_acceptance.py"),
                    str(path),
                    "seed-other-owner",
                ],
                check=True,
            )
            connector_process = launch(
                "connector",
                [
                    str(CONNECTOR / ".venv/bin/python"),
                    script,
                    "connector",
                    "--config",
                    str(path),
                ],
                connector_environment,
                CONNECTOR,
            )
            await_http(http, config.connector_url + "/health", processes)
            agent_process = launch(
                "agent",
                [*agent_command, "agent", "--config", str(path)],
                agent_environment,
                AGENT,
            )
            await_http(http, config.agent_url + "/health", processes)
            acceptance(
                http,
                config,
                ProcessLifecycle(
                    http,
                    config,
                    agent_process,
                    connector_process,
                    launch,
                    agent_command,
                    agent_environment,
                    connector_environment,
                    path,
                ),
            )
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
        for record, process in zip(process_ids, processes, strict=True):
            record.exit_code = process.poll()
        private_json(
            directory / "process-pids.json", [asdict(record) for record in process_ids]
        )
        logs.close()
    print(f"PASS: local process acceptance; private evidence: {directory}")


def projection(http: httpx.Client, config: ProcessConfig, session: str):
    from lux_agent.protocol import (
        OrderedContent,
        TaskEventContent,
        ToolCallContent,
        ToolResultContent,
    )

    class ItemView(View):
        id: str
        user_id: str
        session_id: str
        kind: str
        source: str
        status: str
        payload: (
            OrderedContent
            | TaskEventContent
            | ToolCallContent
            | ToolResultContent
            | None
        )

    class RunView(View):
        id: str
        status: str

    class Projection(View):
        items: tuple[ItemView, ...]
        runs: tuple[RunView, ...]
        last_event_sequence_no: int

    return Projection.model_validate_json(
        request(
            http,
            "GET",
            f"{config.backend_url}/api/v1/users/{USER}/sessions/{session}/items",
            params={"limit": 200},
        ).content
    )


def wait_for(check, description: str, seconds: int = 90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.2)
    raise TimeoutError(description)


def terminate(process: subprocess.Popen) -> None:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=15)


def acceptance(
    http: httpx.Client,
    config: ProcessConfig,
    lifecycle: AcceptanceLifecycle,
) -> None:
    from lux_agent import generation_plan as plans
    from lux_agent.protocol import TaskQuerySnapshot
    from lux_agent.reconciliation import ModelEvidencePage, OriginalModelEvidence
    from provider_fixture import CaseConfiguration, FixtureEvidence

    prefix = f"{config.backend_url}/api/v1/users/{USER}"
    directory = Path(config.directory)
    operator = {"Authorization": "Bearer " + config.operator_token}
    assets = []
    sessions = []
    tasks = []

    def configure(
        marker: str,
        media: Literal["IMAGE", "VIDEO", "MODEL_LOST"],
        asset_id: str | None = None,
        task_id: str | None = None,
    ):
        case = CaseConfiguration(
            marker=marker,
            media_type=media,
            reference_asset_id=asset_id,
            task_id=task_id,
        )
        request(
            http,
            "POST",
            config.fixture_url + "/fixture/cases",
            content=case.model_dump_json(),
            headers={"Content-Type": "application/json"},
        )

    def evidence() -> FixtureEvidence:
        return FixtureEvidence.model_validate_json(
            request(http, "GET", config.fixture_url + "/fixture/evidence").content
        )

    def new_session(marker: str, asset_id: str | None = None) -> str:
        created = SessionView.model_validate_json(
            request(
                http,
                "POST",
                prefix + "/sessions",
                json={"title": marker},
                headers={"Idempotency-Key": str(uuid4())},
            ).content
        )
        sessions.append(created.id)
        content = [{"type": "text", "text": marker}]
        if asset_id:
            content.append({"type": "asset", "asset_id": asset_id})
        payload = {"client_request_id": str(uuid4()), "content": content}
        first = request(
            http, "POST", prefix + f"/sessions/{created.id}/messages", json=payload
        )
        assert (
            request(
                http, "POST", prefix + f"/sessions/{created.id}/messages", json=payload
            ).json()
            == first.json()
        )
        return created.id

    def submitted(session: str) -> str | None:
        for item in projection(http, config, session).items:
            if isinstance(item.payload, plans.PlanTaskSubmissionReceipt):
                assert item.payload.requested_file_count == 1
                return item.payload.tasks[0].task_id
        return None

    def task_snapshot(session: str, task: str) -> TaskQuerySnapshot:
        return TaskQuerySnapshot.model_validate_json(
            request(
                http,
                "GET",
                f"{config.backend_url}/internal/v2/users/{USER}/sessions/{session}/tasks/{task}",
            ).content
        )

    def completed(session: str):
        page = projection(http, config, session)
        return (
            page
            if page.runs and all(run.status == "COMPLETED" for run in page.runs)
            else None
        )

    with open(directory / "reference.png", "rb") as original:
        asset = AssetView.model_validate_json(
            request(
                http,
                "POST",
                prefix + "/assets",
                files={"file": ("reference.png", original, "image/png")},
                headers={"Idempotency-Key": str(uuid4())},
            ).content
        )
    assert (
        asset.sha256
        == hashlib.sha256((directory / "reference.png").read_bytes()).hexdigest()
    )
    assert (
        http.get(
            f"{config.backend_url}/api/v1/users/{OTHER}/assets/{asset.id}"
        ).status_code
        == 404
    )
    assets.append(asset.id)

    configure("LUX_CASE_IMAGE", "IMAGE", asset.id)
    image_session = new_session("LUX_CASE_IMAGE", asset.id)
    image_task = wait_for(
        lambda: submitted(image_session), "Image plan was not submitted"
    )
    tasks.append(image_task)
    image_snapshot = wait_for(
        lambda: (
            value
            if (value := task_snapshot(image_session, image_task)).status == "SUCCEEDED"
            else None
        ),
        "Java did not import retained image bytes",
    )
    assert len(image_snapshot.assets) == 1
    image_asset_id = image_snapshot.assets[0].asset_id
    image_bytes = request(
        http, "GET", prefix + f"/assets/{image_asset_id}/content"
    ).content
    assert image_bytes == (directory / "image.png").read_bytes()
    image_metadata = AssetView.model_validate_json(
        request(http, "GET", prefix + f"/assets/{image_asset_id}").content
    )
    assert image_metadata.sha256 == hashlib.sha256(image_bytes).hexdigest()
    assert (
        request(
            http,
            "GET",
            prefix + f"/assets/{image_asset_id}/content",
            headers={"Range": "bytes=2-5"},
        ).content
        == image_bytes[2:6]
    )
    wait_for(lambda: completed(image_session), "Agent image Run did not complete")
    image_case = next(
        case for case in evidence().cases if case.marker == "LUX_CASE_IMAGE"
    )
    assert image_case.image_blocks > 0
    assert any(
        image_asset_id in image.label and image_task in image.label
        for image in image_case.images
    )
    assert image_case.tool_names[:4] == [
        "get_model_catalog",
        "create_generation_plan",
        "update_generation_plan",
        "submit_generation_plan",
    ]

    def first_sse_id(after: int) -> int:
        with http.stream(
            "GET",
            prefix + f"/sessions/{image_session}/events",
            headers={"Last-Event-ID": str(after)},
        ) as stream:
            stream.raise_for_status()
            assert stream.headers["content-type"].startswith("text/event-stream")
            for line in stream.iter_lines():
                if line.startswith("id:"):
                    return int(line.removeprefix("id:").strip())
        raise AssertionError("SSE replay contained no event identity")

    first_id = first_sse_id(0)
    assert first_sse_id(first_id) > first_id

    configure("LUX_CASE_VIDEO", "VIDEO", asset.id)
    video_session = new_session("LUX_CASE_VIDEO", asset.id)
    video_task = wait_for(
        lambda: submitted(video_session), "Video plan was not submitted"
    )
    tasks.append(video_task)
    wait_for(
        lambda: evidence().video_posts == 1,
        "Video fixture did not receive original POST",
    )
    route = (
        f"{config.connector_url}/internal/provider-reconciliation/{USER}/{video_task}"
    )
    wait_for(
        lambda: (
            response
            if (response := http.get(route, headers=operator)).status_code == 200
            else None
        ),
        "Unresolved original video evidence unavailable",
    )
    assert http.get(route).status_code == 401
    before = request(http, "GET", route, headers=operator).json()
    assert before["external_id"] is None
    assert task_snapshot(video_session, video_task).status != "SUCCEEDED"
    # Keep the persisted MySQL and consumer group while replacing only the worker.
    lifecycle.restart_connector()
    assert evidence().video_posts == 1
    attachment = {
        "binding": before["binding"],
        "external_id": "seedance_original_fixture",
        "evidence_reference": "local:provider-original-video",
        "reason": "Fixture operator recovered original accepted task receipt",
    }
    forged = json.loads(json.dumps(attachment))
    forged["binding"]["account_ref"] = "different-account"
    assert (
        http.post(
            route + "/original-receipt", headers=operator, json=forged
        ).status_code
        == 409
    )
    request(
        http, "POST", route + "/original-receipt", headers=operator, json=attachment
    )
    request(
        http, "POST", route + "/original-receipt", headers=operator, json=attachment
    )
    video_snapshot = wait_for(
        lambda: (
            value
            if (value := task_snapshot(video_session, video_task)).status == "SUCCEEDED"
            else None
        ),
        "Original video GET/preservation/import did not complete",
    )
    assert evidence().video_posts == 1 and evidence().video_gets >= 1
    video_asset_id = video_snapshot.assets[0].asset_id
    video_bytes = request(
        http, "GET", prefix + f"/assets/{video_asset_id}/content"
    ).content
    assert video_bytes == (directory / "video.mp4").read_bytes()
    wait_for(lambda: completed(video_session), "Initial video Run did not complete")
    configure("LUX_OBSERVE_VIDEO", "VIDEO", task_id=video_task)
    request(
        http,
        "POST",
        prefix + f"/sessions/{video_session}/messages",
        json={
            "client_request_id": str(uuid4()),
            "content": [{"type": "text", "text": "LUX_OBSERVE_VIDEO"}],
        },
    )
    wait_for(
        lambda: (
            next(
                case for case in evidence().cases if case.marker == "LUX_OBSERVE_VIDEO"
            ).posts
            >= 2
        ),
        "Fresh task observation was not invoked",
    )
    wait_for(
        lambda: completed(video_session),
        "Fresh task query did not complete video observation",
    )
    observed_case = next(
        case for case in evidence().cases if case.marker == "LUX_OBSERVE_VIDEO"
    )
    assert observed_case.image_blocks > 1
    assert (
        len(
            {
                image.timestamp_seconds
                for image in observed_case.images
                if image.timestamp_seconds is not None
            }
        )
        > 1
    )
    assert all(
        video_asset_id in image.label and video_task in image.label
        for image in observed_case.images
        if image.timestamp_seconds is not None
    )
    assert (
        http.get(
            f"{config.backend_url}/internal/v2/users/{OTHER}/sessions/{video_session}/tasks/{video_task}"
        ).status_code
        == 404
    )

    # Real Java admission rejects a ratio just beyond2.5 without any child Task or provider POST.
    from provider_fixture import create_arguments

    with (directory / "invalid-reference.png").open("rb") as original:
        invalid_asset = AssetView.model_validate_json(
            request(
                http,
                "POST",
                prefix + "/assets",
                files={"file": ("invalid-reference.png", original, "image/png")},
                headers={"Idempotency-Key": str(uuid4())},
            ).content
        )
    negative = SessionView.model_validate_json(
        request(
            http,
            "POST",
            prefix + "/sessions",
            json={"title": "Invalid Seedance ratio"},
            headers={"Idempotency-Key": str(uuid4())},
        ).content
    )
    sessions.append(negative.id)
    context = plans.GenerationPlanContext.model_validate_json(
        request(
            http,
            "GET",
            f"{config.backend_url}/internal/v2/users/{USER}/sessions/{negative.id}/generation-plan-context",
        ).content
    )
    arguments = create_arguments(
        CaseConfiguration(
            marker="ratio-negative",
            media_type="VIDEO",
            reference_asset_id=invalid_asset.id,
        ),
        context.catalog,
    )
    create_invocation = plans.CreateGenerationPlanInvocation(
        type="generation_plan_tool_invocation",
        operation_id=str(uuid4()),
        session_id=negative.id,
        tool_name="create_generation_plan",
        arguments=arguments,
    )
    create_receipt = plans.JavaPlanToolOperationReceipt.model_validate_json(
        request(
            http,
            "POST",
            f"{config.backend_url}/internal/v2/users/{USER}/sessions/{negative.id}/generation-plan-tools/create_generation_plan",
            content=create_invocation.model_dump_json(),
            headers={"Content-Type": "application/json"},
        ).content
    )
    assert isinstance(create_receipt.result, plans.PlanToolRejection)
    assert create_receipt.result.business_execution == "NOT_PERFORMED"
    assert any(
        issue.code == "REFERENCE_LIMIT_EXCEEDED" and "aspect ratio" in issue.message
        for issue in create_receipt.result.issues
    )
    assert evidence().video_posts == 1

    configure("LUX_LOST_OPENAI", "MODEL_LOST")
    lost_session = new_session("LUX_LOST_OPENAI")
    model_route = config.agent_url + "/internal/model-reconciliation"

    def unresolved():
        page = ModelEvidencePage.model_validate_json(
            request(http, "GET", model_route, headers=operator).content
        )
        return next(
            (
                view
                for view in page.executions
                if view.binding.session_id == lost_session
            ),
            None,
        )

    original_model = wait_for(
        unresolved, "Lost original model response was not persisted"
    )
    assert original_model.response_id is None
    assert http.get(model_route).status_code == 401
    checkpoint_before = (
        lifecycle.inspect_checkpoints() if lifecycle.container_proof else None
    )
    lifecycle.restart_agent()
    if checkpoint_before is not None:
        checkpoint_after = lifecycle.inspect_checkpoints()
        checkpoint_before.require_retained(checkpoint_after)
        private_json(
            directory / "checkpoint-evidence.json",
            asdict(
                CheckpointRetentionEvidence(checkpoint_before, checkpoint_after, True)
            ),
        )
    assert (
        next(
            case for case in evidence().cases if case.marker == "LUX_LOST_OPENAI"
        ).posts
        == 1
    )
    attachment_model = OriginalModelEvidence(
        binding=original_model.binding,
        run_id=original_model.run_id,
        turn_id=original_model.turn_id,
        response_id="resp_lost_original_fixture",
        evidence_reference="local:original-openai-response",
        reason="Fixture operator recovered original accepted response",
    )
    request(
        http,
        "POST",
        model_route + f"/{original_model.binding.execution_id}/original-receipt",
        content=attachment_model.model_dump_json(),
        headers={**operator, "Content-Type": "application/json"},
    )
    wait_for(
        lambda: completed(lost_session),
        "Original OpenAI GET did not recover interrupted Run",
    )
    final = evidence()
    assert final.response_gets >= 1
    assert (
        next(case for case in final.cases if case.marker == "LUX_LOST_OPENAI").posts
        == 1
    )
    assert final.image_posts == final.video_posts == 1
    assert final.provider_reference_sha256 == [asset.sha256, asset.sha256]
    model_evidence = lifecycle.inspect_model(original_model.binding.execution_id)
    assert model_evidence.audit_count == 1
    assert model_evidence.response_id == "resp_lost_original_fixture"
    assert model_evidence.requests_omit_embedded_images
    audit_count = model_evidence.audit_count
    private_json(
        directory / "database-evidence.json", json.loads(lifecycle.check_database())
    )
    if lifecycle.container_proof:
        marker = "LUX_OBSERVE_VIDEO_AFTER_RESTART"
        configure(marker, "VIDEO", task_id=video_task)
        request(
            http,
            "POST",
            prefix + f"/sessions/{video_session}/messages",
            json={
                "client_request_id": str(uuid4()),
                "content": [{"type": "text", "text": marker}],
            },
        )
        wait_for(
            lambda: (
                next(case for case in evidence().cases if case.marker == marker).posts
                >= 2
            ),
            "Post-restart Session input did not invoke the original Task observation",
        )
        wait_for(
            lambda: completed(video_session),
            "Post-restart video observation did not complete",
        )
        resumed = next(case for case in evidence().cases if case.marker == marker)
        frames = [
            image for image in resumed.images if image.timestamp_seconds is not None
        ]
        assert len({image.timestamp_seconds for image in frames}) > 1
        assert all(
            video_asset_id in image.label and video_task in image.label
            for image in frames
        )
        before_java = [
            TaskView.model_validate_json(
                request(http, "GET", prefix + f"/tasks/{task}").content
            )
            for task in (image_task, video_task)
        ]
        before_replay = first_sse_id(first_id)
        lifecycle.restart_java()
        after_java = [
            TaskView.model_validate_json(
                request(http, "GET", prefix + f"/tasks/{task}").content
            )
            for task in (image_task, video_task)
        ]
        assert after_java == before_java
        for retained_id, retained_bytes in (
            (image_asset_id, image_bytes),
            (video_asset_id, video_bytes),
        ):
            metadata = AssetView.model_validate_json(
                request(http, "GET", prefix + f"/assets/{retained_id}").content
            )
            assert (
                request(http, "GET", prefix + f"/assets/{retained_id}/content").content
                == retained_bytes
            )
            assert metadata.sha256 == hashlib.sha256(retained_bytes).hexdigest()
            ranged = request(
                http,
                "GET",
                prefix + f"/assets/{retained_id}/content",
                headers={"Range": "bytes=2-5"},
            )
            assert ranged.status_code == 206 and ranged.content == retained_bytes[2:6]
        assert first_sse_id(first_id) == before_replay
        final = evidence()
        assert final.image_posts == final.video_posts == 1
        assert (
            next(case for case in final.cases if case.marker == "LUX_LOST_OPENAI").posts
            == 1
        )
        private_json(
            directory / "database-evidence.json", json.loads(lifecycle.check_database())
        )
    image_task_view = TaskView.model_validate_json(
        request(http, "GET", prefix + f"/tasks/{image_task}").content
    )
    video_task_view = TaskView.model_validate_json(
        request(http, "GET", prefix + f"/tasks/{video_task}").content
    )
    assert (
        image_task_view.plan_id
        and video_task_view.plan_id
        and image_task_view.plan_id != video_task_view.plan_id
    )
    assert (
        image_task_view.assets[0].id == image_asset_id
        and video_task_view.assets[0].id == video_asset_id
    )
    private_json(
        directory / "acceptance-evidence.json",
        {
            "fixture": final.model_dump(),
            "sessions": sessions,
            "tasks": tasks,
            "input_asset_id": asset.id,
            "image_asset_id": image_asset_id,
            "video_asset_id": video_asset_id,
            "model_audit_rows": audit_count,
        },
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "fixture", "agent", "connector"))
    parser.add_argument("--services", type=Path)
    parser.add_argument("--java-jar", type=Path)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--bind-address", default="127.0.0.1")
    options = parser.parse_args()
    if options.mode == "run":
        if options.services is None or options.java_jar is None:
            parser.error("run requires --services and --java-jar")
        run(options.services.resolve(), options.java_jar.resolve())
    else:
        if options.config is None:
            parser.error("worker requires --config")
        worker(options.mode, options.config, options.bind_address)
