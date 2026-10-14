"""Offline regressions for owned control operations and restart retention proof."""

import sqlite3
import subprocess
from dataclasses import replace
from pathlib import Path

import pytest
from agent_inspection import CheckpointEvidence, checkpoints, model_store
from compose_acceptance import (
    ComposeLifecycle,
    ComposeManifest,
    ContainerConfig,
    ContainerInspection,
    ContainerState,
    NetworkSettings,
    OwnedContainer,
)
from process_acceptance import ProcessConfig


@pytest.fixture
def lifecycle(tmp_path: Path) -> ComposeLifecycle:
    config = ProcessConfig(
        str(tmp_path),
        "http://127.0.0.1:8080",
        "http://127.0.0.1:8000",
        "http://127.0.0.1:8001",
        "http://127.0.0.1:8003",
        "kafka:9092",
        "mysql",
        3306,
        "fixture-password",
        "lux_process_backend_123456abcdef",
        "lux_process_connector_123456abcdef",
        "http://seaweedfs:8333",
        "fixture-access",
        "fixture-secret",
        "fixture-token",
    )
    return ComposeLifecycle(
        tmp_path,
        ComposeManifest(
            "123456abcdef",
            "lux-it-123456abcdef",
            "unix:///fixture.sock",
            config,
            config,
        ),
    )


def container(
    lifecycle: ComposeLifecycle, *, owner: str | None = None, started: str = "before"
) -> ContainerInspection:
    return ContainerInspection(
        identity="original-container",
        image="sha256:original-image",
        config=ContainerConfig(
            user="10001:10001",
            labels={
                "lux.integration.owner": owner or lifecycle.manifest.owner,
                "com.docker.compose.project": lifecycle.manifest.project,
                "com.docker.compose.service": "agent",
            },
        ),
        state=ContainerState(
            running=True, oom_killed=False, exit_code=0, started_at=started
        ),
        mounts=(),
        network_settings=NetworkSettings(networks={}),
    )


def record(lifecycle: ComposeLifecycle, current: ContainerInspection) -> None:
    lifecycle.manifest.containers.append(
        OwnedContainer(
            "agent",
            current.identity,
            current.image,
            current.config.user,
            current.mounts,
            {},
            current.state,
        )
    )


def test_owner_mismatch_blocks_restart_and_cleanup_before_control(
    lifecycle: ComposeLifecycle, monkeypatch
):
    record(lifecycle, container(lifecycle))
    monkeypatch.setattr(
        lifecycle,
        "inspect",
        lambda identity: container(lifecycle, owner="some-other-owner"),
    )
    operations: list[tuple[str, ...]] = []
    monkeypatch.setattr(
        lifecycle, "docker", lambda *args, **kwargs: operations.append(args)
    )
    with pytest.raises(ValueError, match="owner mismatch"):
        lifecycle.restart_agent()
    with pytest.raises(ValueError, match="owner mismatch"):
        lifecycle.cleanup()
    assert not operations


def test_changed_image_blocks_readonly_exec_and_restart(
    lifecycle: ComposeLifecycle, monkeypatch
):
    record(lifecycle, container(lifecycle))
    monkeypatch.setattr(
        lifecycle,
        "inspect",
        lambda identity: container(lifecycle).model_copy(
            update={"image": "sha256:replacement"}
        ),
    )
    with pytest.raises(ValueError, match="identity changed"):
        lifecycle.inspect_checkpoints()
    with pytest.raises(ValueError, match="identity changed"):
        lifecycle.restart_agent()


def test_uncertain_restart_reconciles_original_id_without_repeating(
    lifecycle: ComposeLifecycle, monkeypatch
):
    record(lifecycle, container(lifecycle))
    operations: list[tuple[str, ...]] = []
    restarted = False

    def inspect(identity: str) -> ContainerInspection:
        assert identity == "original-container"
        return container(lifecycle, started="after" if restarted else "before")

    def docker(*arguments: str, **kwargs) -> str:
        nonlocal restarted
        operations.append(arguments)
        restarted = True
        raise subprocess.TimeoutExpired(arguments, 45)

    monkeypatch.setattr(lifecycle, "inspect", inspect)
    monkeypatch.setattr(lifecycle, "docker", docker)
    monkeypatch.setattr(lifecycle, "healthy_http", lambda *args: None)
    lifecycle.restart_agent()
    assert operations == [("restart", "--time", "15", "original-container")]
    assert lifecycle.manifest.restarts[0].after.started_at == "after"


def test_uncertain_restart_without_changed_start_stops(
    lifecycle: ComposeLifecycle, monkeypatch
):
    record(lifecycle, container(lifecycle))
    monkeypatch.setattr(lifecycle, "inspect", lambda identity: container(lifecycle))

    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired(args, 45)

    monkeypatch.setattr(lifecycle, "docker", timeout)
    with pytest.raises(RuntimeError, match="without a changed start"):
        lifecycle.restart_agent()
    assert not lifecycle.manifest.restarts


def test_checkpoint_proof_requires_existing_keys_and_blobs_and_allows_new_rows(
    tmp_path: Path,
):
    store = tmp_path / "agent.db"
    with sqlite3.connect(str(store) + ".checkpoints") as database:
        database.execute(
            "CREATE TABLE checkpoints(thread_id TEXT, checkpoint_ns TEXT, checkpoint_id TEXT, checkpoint BLOB, metadata BLOB)"
        )
        database.execute(
            "INSERT INTO checkpoints VALUES('session-original','','checkpoint-original',?,?)",
            (b"original-bytes", b"original-metadata"),
        )
    before = checkpoints(store)
    with sqlite3.connect(str(store) + ".checkpoints") as database:
        database.execute(
            "INSERT INTO checkpoints VALUES('session-original','','checkpoint-later',?,?)",
            (b"later-bytes", b"later-metadata"),
        )
    before.require_retained(checkpoints(store))
    with sqlite3.connect(str(store) + ".checkpoints") as database:
        database.execute(
            "UPDATE checkpoints SET checkpoint=? WHERE checkpoint_id='checkpoint-original'",
            (b"changed",),
        )
    with pytest.raises(AssertionError, match="lost or changed"):
        before.require_retained(checkpoints(store))
    with pytest.raises(AssertionError, match="No checkpoint rows"):
        CheckpointEvidence(()).require_retained(before)
    with pytest.raises(AssertionError, match="lost or changed"):
        before.require_retained(
            CheckpointEvidence((replace(before.rows[0], checkpoint_id="replaced-key"),))
        )


def test_model_inspector_reads_original_receipt_and_rejects_missing_store(
    tmp_path: Path,
):
    store = tmp_path / "agent.db"
    with sqlite3.connect(store) as database:
        database.execute("CREATE TABLE model_reconciliation_audit(id INTEGER)")
        database.execute("INSERT INTO model_reconciliation_audit VALUES(1)")
        database.execute(
            "CREATE TABLE model_provider_receipt(execution_id TEXT,response_id TEXT)"
        )
        database.execute(
            "INSERT INTO model_provider_receipt VALUES('original','resp_original')"
        )
        database.execute(
            "CREATE TABLE execution_receipt(kind TEXT,request_snapshot TEXT)"
        )
        database.execute(
            "INSERT INTO execution_receipt VALUES('MODEL','safe archived input')"
        )
    evidence = model_store(store, "original")
    assert evidence.audit_count == 1 and evidence.response_id == "resp_original"
    assert evidence.requests_omit_embedded_images
    with sqlite3.connect(store) as database:
        database.execute(
            "INSERT INTO execution_receipt VALUES('MODEL','data:image/png;base64,omitted')"
        )
    assert not model_store(store, "original").requests_omit_embedded_images
    with pytest.raises(sqlite3.OperationalError):
        model_store(tmp_path / "missing.db", "original")
    assert not (tmp_path / "missing.db").exists()


def test_created_container_network_resolves_owned_identity_without_start(
    lifecycle: ComposeLifecycle, monkeypatch
):
    import json

    from compose_acceptance import NetworkAttachment

    current = container(lifecycle).model_copy(
        update={
            "network_settings": NetworkSettings(
                networks={"exclusive-network": NetworkAttachment(identity="")}
            )
        }
    )
    operations: list[tuple[str, ...]] = []

    def docker(*arguments: str, **kwargs) -> str:
        operations.append(arguments)
        return json.dumps(
            [
                {
                    "Name": "exclusive-network",
                    "Id": "original-network-id",
                    "Labels": {
                        "lux.integration.owner": lifecycle.manifest.owner,
                        "com.docker.compose.project": lifecycle.manifest.project,
                    },
                }
            ]
        )

    monkeypatch.setattr(lifecycle, "docker", docker)
    assert lifecycle.network_ids(current) == {
        "exclusive-network": "original-network-id"
    }
    assert operations == [("network", "inspect", "exclusive-network")]


def test_unowned_volume_blocks_entire_cleanup_before_removal(
    lifecycle: ComposeLifecycle, monkeypatch
):
    import json

    from compose_acceptance import OwnedResource

    record(lifecycle, container(lifecycle))
    lifecycle.manifest.resources.append(
        OwnedResource("volume", "recorded-volume", "recorded-volume")
    )
    monkeypatch.setattr(lifecycle, "inspect", lambda identity: container(lifecycle))
    operations: list[tuple[str, ...]] = []

    def docker(*arguments: str, **kwargs) -> str:
        operations.append(arguments)
        return json.dumps(
            [
                {
                    "Name": "recorded-volume",
                    "Labels": {
                        "lux.integration.owner": "other-owner",
                        "com.docker.compose.project": lifecycle.manifest.project,
                    },
                }
            ]
        )

    monkeypatch.setattr(lifecycle, "docker", docker)
    with pytest.raises(ValueError, match="owner mismatch"):
        lifecycle.cleanup()
    assert operations == [("volume", "inspect", "recorded-volume")]


def test_fixture_oom_stops_host_http_assertions(
    lifecycle: ComposeLifecycle, monkeypatch
):
    import json

    import httpx

    initial = container(lifecycle)
    fixture = initial.model_copy(
        update={
            "identity": "original-fixture",
            "config": initial.config.model_copy(
                update={
                    "labels": {
                        **initial.config.labels,
                        "com.docker.compose.service": "fixture",
                    }
                }
            ),
        }
    )
    lifecycle.manifest.containers.append(
        OwnedContainer(
            "fixture",
            fixture.identity,
            fixture.image,
            fixture.config.user,
            fixture.mounts,
            {},
            fixture.state,
        )
    )
    failed = fixture.model_copy(
        update={
            "state": fixture.state.model_copy(
                update={"running": False, "oom_killed": True, "exit_code": 137}
            )
        }
    )
    monkeypatch.setattr(
        lifecycle,
        "docker",
        lambda *args, **kwargs: json.dumps([failed.model_dump(by_alias=True)]),
    )
    with pytest.raises(RuntimeError, match="fixture exited or was OOM-killed"):
        lifecycle.check_liveness(
            httpx.Request("GET", "http://127.0.0.1:8003/fixture/evidence")
        )


def test_partial_create_records_and_removes_unmounted_owned_resources(
    lifecycle: ComposeLifecycle, monkeypatch
):
    import json

    present = {"orphan-network-id", "orphan-volume"}
    operations: list[tuple[str, ...]] = []

    def docker(*arguments: str, **kwargs) -> str:
        operations.append(arguments)
        if arguments[:2] == ("ps", "-aq"):
            return ""
        if arguments[:3] == ("network", "ls", "--quiet"):
            return "orphan-network-id" if "orphan-network-id" in present else ""
        if arguments[:3] == ("volume", "ls", "--quiet"):
            return "orphan-volume" if "orphan-volume" in present else ""
        if len(arguments) >= 3 and arguments[1] == "inspect":
            identity = arguments[2]
            if identity not in present:
                raise subprocess.CalledProcessError(
                    1, arguments, stderr="Error: No such volume or network"
                )
            return json.dumps(
                [
                    {
                        "Name": "orphan-network"
                        if arguments[0] == "network"
                        else identity,
                        "Id": identity if arguments[0] == "network" else None,
                        "Labels": {
                            "lux.integration.owner": lifecycle.manifest.owner,
                            "com.docker.compose.project": lifecycle.manifest.project,
                        },
                    }
                ]
            )
        if len(arguments) >= 3 and arguments[1] == "rm":
            # Every resource must be recorded before the first deletion.
            recorded = json.loads(
                (lifecycle.directory / "compose-manifest.json").read_text()
            )
            assert {item["identity"] for item in recorded["resources"]} == {
                "orphan-network-id",
                "orphan-volume",
            }
            present.remove(arguments[2])
            return ""
        return ""

    monkeypatch.setattr(lifecycle, "docker", docker)
    lifecycle.record_resources()
    assert {item.identity for item in lifecycle.manifest.resources} == present
    assert not lifecycle.manifest.containers
    lifecycle.cleanup()
    assert not present
    controls = [args for args in operations if len(args) >= 2 and args[1] == "rm"]
    assert controls == [
        ("network", "rm", "orphan-network-id"),
        ("volume", "rm", "orphan-volume"),
    ]
    lifecycle.cleanup()
    assert [
        args for args in operations if len(args) >= 2 and args[1] == "rm"
    ] == controls


def test_partial_create_foreign_project_volume_is_rejected_without_controls(
    lifecycle: ComposeLifecycle, monkeypatch
):
    import json

    operations: list[tuple[str, ...]] = []

    def docker(*arguments: str, **kwargs) -> str:
        operations.append(arguments)
        if arguments[:3] == ("volume", "ls", "--quiet"):
            return "foreign-volume"
        if arguments[:2] == ("volume", "inspect"):
            return json.dumps(
                [
                    {
                        "Name": "foreign-volume",
                        "Labels": {
                            "lux.integration.owner": "foreign-owner",
                            "com.docker.compose.project": lifecycle.manifest.project,
                        },
                    }
                ]
            )
        return ""

    monkeypatch.setattr(lifecycle, "docker", docker)
    with pytest.raises(ValueError, match="owner mismatch"):
        lifecycle.record_resources()
    with pytest.raises(ValueError, match="owner mismatch"):
        lifecycle.cleanup()
    assert not lifecycle.manifest.resources
    assert not any(
        args[0] in {"create", "start", "restart", "rm"}
        or len(args) > 1
        and args[1] == "rm"
        for args in operations
    )


def test_existing_project_scope_blocks_create_before_control(
    lifecycle: ComposeLifecycle, monkeypatch
):
    operations: list[tuple[str, ...]] = []

    def docker(*arguments: str, **kwargs) -> str:
        operations.append(arguments)
        return (
            "existing-orphan-volume"
            if arguments[:3] == ("volume", "ls", "--quiet")
            else ""
        )

    monkeypatch.setattr(lifecycle, "docker", docker)
    with pytest.raises(ValueError, match="already contains Docker resources"):
        lifecycle.require_fresh_project()
    lifecycle.cleanup()
    assert not lifecycle.manifest.resources and not lifecycle.manifest.containers
    assert not any("create" in args or "rm" in args for args in operations)


def test_short_listing_persists_full_identity_for_restart_capture_and_cleanup(
    lifecycle: ComposeLifecycle, monkeypatch
):
    import json

    full_id = "123456abcdef" + "0" * 52
    short_id = full_id[:12]
    current = container(lifecycle).model_copy(update={"identity": full_id})
    present = True
    operations: list[tuple[str, ...]] = []

    def docker(*arguments: str, **kwargs) -> str:
        nonlocal current, present
        operations.append(arguments)
        if arguments[:2] == ("ps", "-aq"):
            return short_id if present else ""
        if arguments[:1] == ("inspect",):
            assert arguments[1] in {full_id, short_id}
            return json.dumps([current.model_dump(by_alias=True)])
        if arguments[:1] == ("restart",):
            assert arguments[-1] == full_id
            current = current.model_copy(
                update={
                    "state": current.state.model_copy(update={"started_at": "after"})
                }
            )
        if arguments[:2] == ("rm", "--force"):
            assert arguments[-1] == full_id
            present = False
        return ""

    def logs(command, **kwargs):
        assert command[-1] == full_id
        return subprocess.CompletedProcess(command, 0, stdout="fixture logs\n")

    monkeypatch.setattr(lifecycle, "docker", docker)
    monkeypatch.setattr(lifecycle, "healthy_http", lambda *args: None)
    monkeypatch.setattr(subprocess, "run", logs)
    lifecycle.record_resources()
    lifecycle.record_resources()
    assert len(lifecycle.manifest.containers) == 1
    assert lifecycle.owned("agent").identity == full_id
    lifecycle.restart_agent()
    lifecycle.capture_logs()
    assert (lifecycle.directory / "agent.log").read_text() == "fixture logs\n"
    lifecycle.cleanup()
    assert ("restart", "--time", "15", full_id) in operations
    assert ("rm", "--force", full_id) in operations
    assert all("--no-trunc" in args for args in operations if args[:2] == ("ps", "-aq"))


def test_mount_order_is_not_identity_but_mount_fields_remain_strict(
    lifecycle: ComposeLifecycle, monkeypatch
):
    from compose_acceptance import MountIdentity

    first = MountIdentity(
        kind="volume",
        name="owned-first",
        source="/volumes/first",
        destination="/a",
        writable=True,
    )
    second = MountIdentity(
        kind="volume",
        name="owned-second",
        source="/volumes/second",
        destination="/b",
        writable=False,
    )
    saved = container(lifecycle).model_copy(update={"mounts": (second, first)})
    record(lifecycle, saved)
    raw = saved.model_dump(by_alias=True)
    raw["Mounts"] = [first.model_dump(by_alias=True), second.model_dump(by_alias=True)]
    reordered = ContainerInspection.model_validate(raw)
    monkeypatch.setattr(lifecycle, "inspect", lambda identity: reordered)
    lifecycle.require_container(lifecycle.owned("agent"))
    for changed in (
        first.model_copy(update={"source": "/foreign/source"}),
        first.model_copy(update={"name": "foreign-volume"}),
        first.model_copy(update={"writable": False}),
    ):
        raw["Mounts"] = [
            changed.model_dump(by_alias=True),
            second.model_dump(by_alias=True),
        ]
        current = ContainerInspection.model_validate(raw)
        monkeypatch.setattr(
            lifecycle, "inspect", lambda identity, current=current: current
        )
        with pytest.raises(ValueError, match="identity changed"):
            lifecycle.require_container(lifecycle.owned("agent"))
