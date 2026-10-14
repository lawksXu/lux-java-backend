"""Translate actual Java-frozen requests through the Connector without external IO.

First run Java BytePlusCatalogTests. Then run from lux-python-model-connector:
  .venv/bin/python ../lux-java-backend/scripts/integration/verify_byteplus_contracts.py
This checks serialization/registration/translation, not broker or provider behavior.
"""

import asyncio
import json
from dataclasses import dataclass
from pathlib import Path

import httpx
from lux_model_connector.byteplus import BytePlusClient, BytePlusSettings
from lux_model_connector.byteplus_adapters import byteplus_registry
from lux_model_connector.generation_plan import (
    ImageGenerationRequest,
    decode_generation_request,
)
from lux_model_connector.protocol import GenerationKind
from lux_model_connector.results import S3ResultStore


class TranslationOnlyResults(S3ResultStore):
    """No storage client is constructed; translation must never perform storage IO."""

    def __init__(self) -> None:
        pass


@dataclass(frozen=True)
class ContractCase:
    model_id: str
    media_type: GenerationKind


CASES = (
    ContractCase("seedream-4.5", "IMAGE"),
    ContractCase("seedream-5.0-lite", "IMAGE"),
    ContractCase("seedream-5.0-pro", "IMAGE"),
    ContractCase("seedance-2.5", "VIDEO"),
)


def forbid_http(request: httpx.Request) -> httpx.Response:
    raise AssertionError(f"Contract translation attempted HTTP {request.method}")


async def main() -> None:
    directory = (
        Path(__file__).resolve().parents[2] / "target/integration/byteplus-requests"
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(forbid_http)) as http:
        client = BytePlusClient(BytePlusSettings(api_key="offline-fixture"), http)
        registry = byteplus_registry(client, TranslationOnlyResults())
        assert len(registry.registrations) == len(CASES)
        for case in CASES:
            fixture = directory / f"{case.model_id}.json"
            if not fixture.exists():
                raise FileNotFoundError(
                    "Run Java BytePlusCatalogTests before this check"
                )
            request = decode_generation_request(
                json.loads(fixture.read_text()), case.media_type
            )
            registration, adapter = registry.resolve(request)
            assert registration.account_ref == "byteplus-primary"
            assert registration.single_file and registration.result_verification
            assert request.result_expectations.file_count == 1
            payload = await adapter.translate(request, ())
            assert payload["model_id"] == request.provider_binding.provider_model_id
            if isinstance(request, ImageGenerationRequest):
                assert payload["size"] == "2048x2048"
                assert (
                    payload["output_format"] == request.parameters.output_format.lower()
                )
                assert payload["stream"] is False
                if case.model_id.endswith("pro"):
                    assert payload["layer_decomposition"] is False
                    assert "sequential_image_generation" not in payload
                else:
                    assert payload["sequential_image_generation"] == "disabled"
                    assert "layer_decomposition" not in payload
            else:
                assert payload["resolution"] == "720p" and payload["ratio"] == "16:9"
                assert payload["duration"] == 5 and payload["generate_audio"] is False
                assert payload["output_format"] == "mp4"
                assert payload["omni_reference_task_type"] == "reference"
            print(
                f"{case.model_id}: Java JSON -> exact Connector route "
                "-> provider payload OK"
            )
    print("4 cross-project contracts verified; no external IO")


if __name__ == "__main__":
    asyncio.run(main())
