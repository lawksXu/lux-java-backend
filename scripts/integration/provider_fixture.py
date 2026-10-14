"""Deterministic HTTP substitutes; production SDKs, workers and stores remain real.

Run from the Agent virtual environment. Only fixture HTTP endpoints are served.
Original response archives remain in memory across service-under-test restarts.
"""

import base64
import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, Response
from lux_agent import generation_plan as plans
from lux_agent.protocol import TaskQuerySnapshot, ToolError, decode_tool_result
from openai.types.responses import Response as OpenAIResponse
from pydantic import BaseModel, ConfigDict, JsonValue


class InputContent(BaseModel):
    model_config = ConfigDict(extra="allow")
    type: str
    text: str | None = None
    image_url: str | None = None


class ProviderInput(BaseModel):
    model_config = ConfigDict(extra="allow")
    type: str | None = None
    role: str | None = None
    content: list[InputContent] | str | None = None
    output: str | None = None
    call_id: str | None = None


class OpenAIInput(BaseModel):
    model_config = ConfigDict(extra="allow")
    model: str
    input: list[ProviderInput]
    stream: bool


class ImageSubmission(BaseModel):
    model_config = ConfigDict(extra="allow")
    model: str
    prompt: str
    size: str
    output_format: str
    response_format: str
    image: str | list[str] | None = None


class MediaURL(BaseModel):
    url: str


class VideoContent(BaseModel):
    model_config = ConfigDict(extra="allow")
    type: Literal["text", "image_url"]
    text: str | None = None
    image_url: MediaURL | None = None
    role: str | None = None


class VideoSubmission(BaseModel):
    model_config = ConfigDict(extra="allow")
    model: str
    content: list[VideoContent]
    resolution: str
    ratio: str
    duration: int
    generate_audio: bool
    output_format: str


class CaseConfiguration(BaseModel):
    marker: str
    media_type: Literal["IMAGE", "VIDEO", "MODEL_LOST"]
    reference_asset_id: str | None = None
    task_id: str | None = None


class ImageEvidence(BaseModel):
    label: str
    sha256: str
    byte_length: int
    timestamp_seconds: float | None = None


@dataclass
class Case:
    configuration: CaseConfiguration
    posts: int = 0
    image_blocks: int = 0
    tool_names: list[str] = field(default_factory=list)
    response_ids: list[str] = field(default_factory=list)
    images: list[ImageEvidence] = field(default_factory=list)


class CaseEvidence(BaseModel):
    marker: str
    posts: int
    image_blocks: int
    tool_names: list[str]
    response_ids: list[str]
    images: list[ImageEvidence]


class FixtureEvidence(BaseModel):
    cases: list[CaseEvidence]
    image_posts: int
    video_posts: int
    video_gets: int
    response_gets: int
    provider_reference_sha256: list[str]
    original_video_id: str = "seedance_original_fixture"
    lost_response_id: str = "resp_lost_original_fixture"


def response(
    identity: str, output: list[dict[str, JsonValue]], status: str = "completed"
) -> OpenAIResponse:
    # The SDK-owned external record is validated before serialization.
    return OpenAIResponse.model_validate(
        {
            "id": identity,
            "object": "response",
            "created_at": 1,
            "status": status,
            "error": None,
            "incomplete_details": None,
            "model": "gpt-6-luna",
            "output": output,
            "parallel_tool_calls": True,
            "tools": [],
            "tool_choice": "auto",
            "temperature": 1.0,
            "top_p": 1.0,
            "metadata": {},
            "usage": None,
        }
    )


def event(kind: str, **fields: JsonValue) -> bytes:
    return (
        "data: " + json.dumps({"type": kind, "sequence_number": 1, **fields}) + "\n\n"
    ).encode()


def stream_response(saved: OpenAIResponse) -> bytes:
    chunks = [
        event(
            "response.created",
            response=response(saved.id, [], "in_progress").model_dump(mode="json"),
        )
    ]
    for index, item in enumerate(saved.output):
        document = item.model_dump(mode="json")
        if item.type == "function_call":
            pending = {**document, "arguments": "", "status": "in_progress"}
            chunks += [
                event("response.output_item.added", output_index=index, item=pending),
                event(
                    "response.function_call_arguments.delta",
                    output_index=index,
                    item_id=item.id,
                    delta=item.arguments,
                ),
            ]
        elif item.type == "message":
            chunks += [
                event(
                    "response.output_item.added",
                    output_index=index,
                    item={**document, "content": []},
                ),
                event(
                    "response.content_part.added",
                    output_index=index,
                    item_id=item.id,
                    content_index=0,
                    part={"type": "output_text", "text": "", "annotations": []},
                ),
                event(
                    "response.output_text.delta",
                    output_index=index,
                    item_id=item.id,
                    content_index=0,
                    delta=item.content[0].text,
                ),
                event(
                    "response.output_text.done",
                    output_index=index,
                    item_id=item.id,
                    content_index=0,
                    text=item.content[0].text,
                ),
            ]
    chunks.append(event("response.completed", response=saved.model_dump(mode="json")))
    return b"".join(chunks)


def tool_output(name: str, arguments: BaseModel) -> list[dict[str, JsonValue]]:
    return [
        {
            "type": "function_call",
            "id": "fc_" + uuid4().hex,
            "call_id": "call_" + uuid4().hex,
            "name": name,
            "arguments": arguments.model_dump_json(),
            "status": "completed",
        }
    ]


def text_output(text: str) -> list[dict[str, JsonValue]]:
    return [
        {
            "type": "message",
            "id": "msg_" + uuid4().hex,
            "role": "assistant",
            "status": "completed",
            "content": [{"type": "output_text", "text": text, "annotations": []}],
        }
    ]


def create_arguments(
    case: CaseConfiguration, catalog: plans.ModelCatalogSnapshot
) -> BaseModel:
    media = case.media_type
    model_id = "seedream-5.0-lite" if media == "IMAGE" else "seedance-2.5"
    model = next(model for model in catalog.models if model.model_id == model_id)
    choice = plans.ModelChoice(
        model_id=model_id,
        profile_id="standard",
        expected_capability_revision=model.capability_revision,
    )
    references = (
        (
            plans.ReferenceAsset(
                type="reference_asset", asset_id=case.reference_asset_id
            ),
        )
        if case.reference_asset_id
        else ()
    )
    if media == "IMAGE":
        return plans.CreateImagePlanArguments(
            target=plans.ImageTarget(media_type="IMAGE", model_choice=choice),
            intent=plans.ImageIntent(
                type="image_intent",
                description=case.marker,
                requirements=plans.ImageRequirements(
                    type="image_requirements",
                    prompt=None,
                    size=None,
                    requested_file_count=1,
                    references=None,
                    output_format=None,
                    generation_mode=None,
                ),
            ),
            parameters=plans.ImageParameters(
                type="image_parameters",
                prompt=case.marker,
                size=None,
                references=references,
                output_format=None,
            ),
            expected_focus_revision=0,
        )
    return plans.CreateVideoPlanArguments(
        target=plans.VideoTarget(media_type="VIDEO", model_choice=choice),
        intent=plans.VideoIntent(
            type="video_intent",
            description=case.marker,
            requirements=plans.VideoRequirements(
                type="video_requirements",
                prompt=None,
                size=None,
                requested_file_count=1,
                references=None,
                output_format=None,
                duration_seconds=None,
                generate_audio=None,
                generation_mode=None,
            ),
        ),
        parameters=plans.VideoParameters(
            type="video_parameters",
            prompt=case.marker,
            size=None,
            references=references,
            output_format=None,
            duration_seconds=None,
            generate_audio=None,
        ),
        expected_focus_revision=0,
    )


def update_arguments(
    receipt: plans.PlanMutationReceipt,
) -> plans.UpdateGenerationPlanArguments:
    keep = plans.KeepChange(op="KEEP")
    if receipt.snapshot.plan.configuration.type == "image_plan_configuration":
        patch = plans.ImagePlanPatch(
            type="image_plan_patch",
            description=plans.SetDescription(
                op="SET", value="Reviewed local image plan"
            ),
            requirements=plans.ImageRequirementsPatch(
                prompt=keep,
                size=keep,
                requested_file_count=keep,
                references=keep,
                output_format=keep,
                generation_mode=keep,
            ),
            parameters=plans.ImageParametersPatch(
                prompt=keep, size=keep, references=keep, output_format=keep
            ),
        )
    else:
        patch = plans.VideoPlanPatch(
            type="video_plan_patch",
            description=plans.SetDescription(
                op="SET", value="Reviewed local video plan"
            ),
            requirements=plans.VideoRequirementsPatch(
                prompt=keep,
                size=keep,
                requested_file_count=keep,
                references=keep,
                output_format=keep,
                duration_seconds=keep,
                generate_audio=keep,
                generation_mode=keep,
            ),
            parameters=plans.VideoParametersPatch(
                prompt=keep,
                size=keep,
                references=keep,
                output_format=keep,
                duration_seconds=keep,
                generate_audio=keep,
            ),
        )
    return plans.UpdateGenerationPlanArguments(
        plan_id=receipt.snapshot.plan.id,
        expected_plan_revision=receipt.snapshot.plan.revision,
        expected_focus_revision=receipt.focus.revision,
        patch=patch,
    )


def create_app(directory: Path, public_url: str) -> FastAPI:
    app = FastAPI()
    cases: dict[str, Case] = {}
    responses: dict[str, OpenAIResponse] = {}
    image_posts = video_posts = video_gets = response_gets = 0
    reference_hashes: list[str] = []

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.post("/fixture/cases")
    def configure(configuration: CaseConfiguration):
        cases[configuration.marker] = Case(configuration)
        return {"status": "configured"}

    @app.get("/fixture/evidence")
    def evidence() -> FixtureEvidence:
        return FixtureEvidence(
            cases=[
                CaseEvidence(
                    marker=key,
                    posts=value.posts,
                    image_blocks=value.image_blocks,
                    tool_names=value.tool_names,
                    response_ids=value.response_ids,
                    images=value.images,
                )
                for key, value in cases.items()
            ],
            image_posts=image_posts,
            video_posts=video_posts,
            video_gets=video_gets,
            response_gets=response_gets,
            provider_reference_sha256=reference_hashes,
        )

    @app.post("/v1/responses")
    def model(request: OpenAIInput):
        matches: list[tuple[int, Case]] = []
        for index, entry in enumerate(request.input):
            if entry.role != "user":
                continue
            texts = (
                [entry.content]
                if isinstance(entry.content, str)
                else [block.text for block in entry.content or [] if block.text]
            )
            for text in texts:
                for marker, candidate in cases.items():
                    if marker in text:
                        matches.append((index, candidate))
        marker_index, case = matches[-1] if matches else (0, None)
        if case is None:
            raise HTTPException(400, "Fixture case marker missing")
        case.posts += 1
        image_count = sum(
            block.type == "input_image"
            for entry in request.input
            if isinstance(entry.content, list)
            for block in entry.content
        )
        if image_count > 8:
            raise HTTPException(400, "Media observation exceeded per-invocation limit")
        case.image_blocks += image_count
        if case.posts > 80:
            raise HTTPException(400, "Fixture request cap exceeded")
        for entry in request.input:
            if not isinstance(entry.content, list):
                continue
            label = next((block.text for block in entry.content if block.text), "")
            for block in entry.content:
                if block.type != "input_image":
                    continue
                if not block.image_url or not block.image_url.startswith(
                    "data:image/jpeg;base64,"
                ):
                    raise HTTPException(
                        400, "Expected actual invocation-local JPEG input_image"
                    )
                body = base64.b64decode(block.image_url.split(",", 1)[1], validate=True)
                if not body.startswith(b"\xff\xd8\xff") or len(body) > 1024 * 1024:
                    raise HTTPException(400, "Invalid bounded image presentation bytes")
                match = re.search(r"frame timestamp ([0-9.]+) seconds", label)
                observed = ImageEvidence(
                    label=label,
                    sha256=hashlib.sha256(body).hexdigest(),
                    byte_length=len(body),
                    timestamp_seconds=float(match.group(1)) if match else None,
                )
                if observed not in case.images:
                    case.images.append(observed)
        identity = "resp_" + uuid4().hex
        if case.configuration.media_type == "MODEL_LOST":
            identity = "resp_lost_original_fixture"
            saved = response(identity, text_output("Original lost response recovered"))
            responses[identity] = saved
            case.response_ids.append(identity)
            return Response(b"", media_type="text/event-stream")
        outputs = [
            entry.output
            for entry in request.input[marker_index:]
            if entry.type == "function_call_output" and entry.output
        ]
        if case.configuration.task_id and not outputs:
            from lux_agent.protocol import GetTaskArguments

            tool, arguments = (
                "get_task",
                GetTaskArguments(task_id=case.configuration.task_id),
            )
        elif not outputs:
            tool, arguments = (
                "get_model_catalog",
                plans.GetModelCatalogArguments(
                    media_type=case.configuration.media_type
                ),
            )
        else:
            result = decode_tool_result(outputs[-1])
            if isinstance(result, plans.ModelCatalogSnapshot):
                tool, arguments = (
                    "create_generation_plan",
                    create_arguments(case.configuration, result),
                )
            elif isinstance(result, plans.PlanMutationReceipt):
                if result.action == "CREATED":
                    tool, arguments = "update_generation_plan", update_arguments(result)
                else:
                    tool, arguments = (
                        "submit_generation_plan",
                        plans.SubmitGenerationPlanArguments(
                            plan_id=result.snapshot.plan.id,
                            expected_plan_revision=result.snapshot.plan.revision,
                            expected_focus_revision=result.focus.revision,
                        ),
                    )
            elif isinstance(result, plans.PlanTaskSubmissionReceipt):
                from lux_agent.protocol import GetTaskArguments

                tool, arguments = (
                    "get_task",
                    GetTaskArguments(task_id=result.tasks[0].task_id),
                )
            elif isinstance(result, TaskQuerySnapshot) and result.status == "SUCCEEDED":
                saved = response(
                    identity, text_output("Verified generated media observed")
                )
                responses[identity] = saved
                case.response_ids.append(identity)
                return Response(stream_response(saved), media_type="text/event-stream")
            elif (
                isinstance(result, TaskQuerySnapshot)
                and case.configuration.media_type == "VIDEO"
            ):
                saved = response(
                    identity,
                    text_output(
                        "Original video receipt requires operator reconciliation"
                    ),
                )
                responses[identity] = saved
                case.response_ids.append(identity)
                return Response(stream_response(saved), media_type="text/event-stream")
            elif isinstance(result, TaskQuerySnapshot):
                from lux_agent.protocol import GetTaskArguments

                tool, arguments = "get_task", GetTaskArguments(task_id=result.task_id)
            elif isinstance(result, (plans.PlanToolRejection, ToolError)):
                raise HTTPException(
                    400,
                    f"Unexpected real business rejection: {result.model_dump_json()}",
                )
            else:
                raise HTTPException(400, "Unexpected real tool result")
        case.tool_names.append(tool)
        saved = response(identity, tool_output(tool, arguments))
        responses[identity] = saved
        case.response_ids.append(identity)
        return Response(stream_response(saved), media_type="text/event-stream")

    @app.get("/v1/responses/{identity}")
    def retrieve(identity: str):
        nonlocal response_gets
        response_gets += 1
        if identity not in responses:
            raise HTTPException(404, "Unknown original response")
        return responses[identity].model_dump(mode="json")

    @app.post("/api/v3/images/generations")
    def image(submission: ImageSubmission):
        nonlocal image_posts
        image_posts += 1
        assert submission.model == "seedream-5-0-260128"
        assert submission.size == "2048x2048" and submission.output_format == "png"
        assert submission.response_format == "url"
        urls = (
            [submission.image]
            if isinstance(submission.image, str)
            else submission.image or []
        )
        assert len(urls) == 1
        for url in urls:
            reference = base64.b64decode(url.split(",", 1)[1], validate=True)
            assert reference == (directory / "reference.png").read_bytes()
            reference_hashes.append(hashlib.sha256(reference).hexdigest())
        return {"data": [{"url": public_url + "/fixture/image.png"}]}

    @app.post("/api/v3/contents/generations/tasks")
    def video(submission: VideoSubmission):
        nonlocal video_posts
        video_posts += 1
        assert submission.model == "dreamina-seedance-2-5-260628"
        assert submission.resolution == "720p" and submission.ratio == "16:9"
        assert submission.duration == 5 and not submission.generate_audio
        assert submission.output_format == "mp4"
        references = [part for part in submission.content if part.type == "image_url"]
        assert len(references) == 1 and references[0].role == "reference_image"
        reference = base64.b64decode(
            references[0].image_url.url.split(",", 1)[1], validate=True
        )
        assert reference == (directory / "reference.png").read_bytes()
        reference_hashes.append(hashlib.sha256(reference).hexdigest())
        # The accepted task exists in the fixture; its receipt is deliberately lost.
        return Response(b'{"accepted":true}', media_type="application/json")

    @app.get("/api/v3/contents/generations/tasks/{identity}")
    def query_video(identity: str):
        nonlocal video_gets
        video_gets += 1
        if identity != "seedance_original_fixture":
            raise HTTPException(404, "Unknown original video")
        return {
            "id": identity,
            "status": "succeeded",
            "content": {"video_url": public_url + "/fixture/video.mp4"},
            "model": "dreamina-seedance-2-5-260628",
            "duration": 5,
            "resolution": "720p",
            "ratio": "16:9",
            "generate_audio": False,
            "output_format": "mp4",
        }

    @app.get("/fixture/{filename}")
    def media(filename: str):
        if filename not in {"image.png", "video.mp4"}:
            raise HTTPException(404, "Unknown fixture")
        return FileResponse(directory / filename)

    return app
