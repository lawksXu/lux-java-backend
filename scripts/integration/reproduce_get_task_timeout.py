"""Offline regression: get_task timeout settles and Session progress survives restart.

Run from the lux-python-agent checkout:
  .venv/bin/python ../lux-java-backend/scripts/integration/reproduce_get_task_timeout.py
No network calls are made. All SQLite files use a temporary directory.
Exit zero means all recovery assertions passed. No new snapshot replaces the read.
"""

import asyncio
import json
import tempfile
from collections.abc import AsyncIterator
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import httpx
from lux_agent import protocol
from lux_agent.backend import BackendClient
from lux_agent.execution import (
    LiveOutputSlot,
    ModelRequest,
    ModelResponseObserved,
    ModelTextFragment,
    ObservedModelItem,
    ToolExecutionRequest,
)
from lux_agent.runtime import AgentRuntime
from lux_agent.store import AgentStore

USER = "00000000-0000-0000-0000-000000000001"
SESSION = "00000000-0000-0000-0000-000000000002"
TASK = "00000000-0000-0000-0000-000000000003"


class QueryModel:
    live_slots: tuple[LiveOutputSlot, ...] = ()

    def __init__(self) -> None:
        self.attempts = 0

    async def stream(
        self, request: ModelRequest
    ) -> AsyncIterator[ModelTextFragment | ModelResponseObserved]:
        self.attempts += 1
        if self.attempts == 1:
            item = ObservedModelItem(
                kind="TOOL_CALL",
                payload=protocol.GetTaskCallContent(
                    arguments=protocol.GetTaskArguments(task_id=TASK)
                ),
                call_id="query-1",
                tool_name="get_task",
            )
        else:
            item = ObservedModelItem(
                kind="MESSAGE",
                payload=protocol.OrderedContent(
                    blocks=(protocol.TextBlock(text="Read unavailable; continued"),)
                ),
            )
        yield ModelResponseObserved(
            items=(item,), provider_response={"id": "local-fake-response"}
        )


class FakeJava:
    def __init__(self) -> None:
        self.attempts = 0
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def handle(self, request: httpx.Request) -> httpx.Response:
        self.attempts += 1
        if self.attempts == 1:
            self.started.set()
            await self.release.wait()
            raise httpx.ReadTimeout("one transient read timeout", request=request)
        snapshot = protocol.TaskQuerySnapshot(
            type="task_query_snapshot",
            task_id=TASK,
            version=1,
            status="SUCCEEDED",
            assets=(),
            failure=None,
        )
        return httpx.Response(200, content=snapshot.model_dump_json())


class RecordingBackend(BackendClient):
    execution_id: str | None = None

    async def execute(self, request: ToolExecutionRequest):
        self.execution_id = request.execution_id
        return await super().execute(request)


@dataclass(frozen=True)
class Observation:
    phase: str
    tool_receipt: str
    operation_id: str | None
    run_statuses: tuple[str, ...]
    active_turn: bool
    http_attempts: int
    model_attempts: int
    stop_requested: bool
    pending_inputs: int
    incorporated_through: int


def input_envelope(sequence: int, text: str) -> str:
    item_id = str(uuid4())
    return protocol.InputAccepted(
        schema_version=2,
        event_id=item_id,
        occurred_at=datetime.now(UTC),
        user_id=USER,
        session_id=SESSION,
        item=protocol.AcceptedInputItem(
            id=item_id,
            sequence_no=sequence,
            input_sequence_no=sequence,
            source="USER",
            kind="MESSAGE",
            content=protocol.OrderedContent(blocks=(protocol.TextBlock(text=text),)),
        ),
    ).model_dump_json()


async def observe(
    phase: str,
    runtime: AgentRuntime,
    fake_java: FakeJava,
    model: QueryModel,
    backend: RecordingBackend,
) -> None:
    state = await runtime.state(SESSION)
    assert state is not None and backend.execution_id is not None
    receipt = runtime.store.execution(backend.execution_id)
    assert receipt is not None and receipt.state == "CONFIRMED"
    original = next(item for item in state.items if item.kind == "TOOL_RESULT")
    assert original.call_id == "query-1"
    assert isinstance(original.payload, protocol.ToolError)
    assert original.payload.code == "TASK_READ_UNAVAILABLE"
    assert fake_java.attempts == 1
    result = Observation(
        phase=phase,
        tool_receipt=receipt.state,
        operation_id=receipt.operation_id,
        run_statuses=tuple(run.status for run in state.runs),
        active_turn=state.active_turn is not None,
        http_attempts=fake_java.attempts,
        model_attempts=model.attempts,
        stop_requested=state.control.stop_requested,
        pending_inputs=len(state.pending_inputs),
        incorporated_through=state.input_progress.incorporated_through,
    )
    print(json.dumps(asdict(result), sort_keys=True))


async def main() -> None:
    fake_java = FakeJava()
    model = QueryModel()
    with tempfile.TemporaryDirectory(prefix="lux-agent-audit-") as directory:
        path = Path(directory) / "agent.db"
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(fake_java.handle), base_url="http://fake-java"
        ) as client:
            backend = RecordingBackend(client)
            with AgentStore(path) as store:
                store.admit(input_envelope(1, "Read this Task"))
                async with AgentRuntime(
                    store, executor=model, tools=backend, tool_reconciler=backend
                ) as runtime:
                    driving = asyncio.create_task(runtime.advance(SESSION))
                    await asyncio.wait_for(fake_java.started.wait(), timeout=5)
                    store.admit(
                        protocol.StopSession(
                            schema_version=2,
                            event_id=str(uuid4()),
                            occurred_at=datetime.now(UTC),
                            user_id=USER,
                            session_id=SESSION,
                        ).model_dump_json()
                    )
                    store.admit(input_envelope(2, "New input after timeout"))
                    fake_java.release.set()
                    await driving
                    await observe(
                        "stop-after-timeout", runtime, fake_java, model, backend
                    )
                    assert (await runtime.state(SESSION)).runs[-1].status == "CANCELLED"
                    for _ in range(3):
                        await runtime.advance(SESSION)
                    await observe(
                        "new-input-progress", runtime, fake_java, model, backend
                    )
            with AgentStore(path) as store:
                async with AgentRuntime(
                    store, executor=model, tools=backend, tool_reconciler=backend
                ) as runtime:
                    for _ in range(3):
                        await runtime.advance(SESSION)
                    await observe("post-restart", runtime, fake_java, model, backend)
                    state = await runtime.state(SESSION)
                    assert [run.status for run in state.runs] == [
                        "CANCELLED",
                        "COMPLETED",
                    ]
                    assert state.active_turn is None
                    assert state.input_progress.incorporated_through == 2
                    assert model.attempts == 2
    print(
        "get_task recovery verified: one read, original error, "
        "stop/input/restart progress"
    )


if __name__ == "__main__":
    asyncio.run(main())
