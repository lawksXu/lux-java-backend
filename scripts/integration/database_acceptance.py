"""Inspect only the disposable databases created by process_acceptance.py."""

import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import pymysql
from lux_model_connector.generation_plan import NormalizedGenerationRequest
from lux_model_connector.store import ConnectorStore, MySQLSettings
from process_acceptance import USER, ProcessConfig
from pydantic import TypeAdapter


@dataclass(frozen=True)
class PersistedTask:
    identity: str
    status: str
    plan_id: str | None
    operation_id: str
    request_json: str


@dataclass(frozen=True)
class DurableEvidence:
    successful_tasks: int
    imported_assets: int
    frozen_plans: int
    java_delivered_outbox: int
    java_applied_inbox: int
    connector_successful_executions: int
    connector_reconciliation_audits: int


def check(config: ProcessConfig) -> DurableEvidence:
    for database in (config.backend_database, config.connector_database):
        if not re.fullmatch(r"lux_process_(backend|connector)_[0-9a-f]{12}", database):
            raise ValueError(
                "Refusing to inspect a database outside the disposable process scope"
            )
    settings = MySQLSettings(
        host=config.mysql_host,
        port=config.mysql_port,
        user="root",
        password=config.mysql_password,
        database=config.connector_database,
    )
    store = ConnectorStore(settings)
    with (
        pymysql.connect(
            host=config.mysql_host,
            port=config.mysql_port,
            user="root",
            password=config.mysql_password,
            database=config.backend_database,
        ) as connection,
        connection.cursor() as cursor,
    ):
        cursor.execute(
            "SELECT id,status,plan_id,operation_id,payload FROM task WHERE user_id=%s",
            (USER,),
        )
        tasks = [PersistedTask(*row) for row in cursor.fetchall()]
        assert len(tasks) == 2
        assert len({row.operation_id for row in tasks}) == 2
        for task in tasks:
            assert task.status == "SUCCEEDED" and task.plan_id is not None
            decoded = TypeAdapter(NormalizedGenerationRequest).validate_json(
                task.request_json
            )
            assert decoded.provider_binding.provider_id == "byteplus"
            work = store.reconciliation_work(USER, task.identity)
            assert work is not None
            assert work.execution.status == "SUCCEEDED"
            assert work.execution.operation_id == task.operation_id
            archived = work.execution.input_generation_payload.request
            restored = TypeAdapter(NormalizedGenerationRequest).validate_json(
                archived.model_dump_json()
            )
            assert restored == decoded
            assert archived.model_dump() == json.loads(task.request_json)
        cursor.execute("SELECT COUNT(*) FROM asset WHERE task_id IS NOT NULL")
        assets = cursor.fetchone()[0]
        assert assets == 2
        cursor.execute("SELECT COUNT(*) FROM generation_plan WHERE state='FROZEN'")
        plans = cursor.fetchone()[0]
        assert plans == 2
        cursor.execute(
            "SELECT COUNT(*) FROM outbox_event WHERE delivered_at IS NOT NULL"
        )
        outbox = cursor.fetchone()[0]
        assert outbox >= 6
        cursor.execute("SELECT COUNT(*) FROM inbox_event WHERE status='APPLIED'")
        inbox = cursor.fetchone()[0]
        assert inbox > 0
    with store.connection() as connection, connection.cursor() as cursor:
        cursor.execute(
            "SELECT COUNT(*) FROM generation_execution WHERE status='SUCCEEDED'"
        )
        executions = cursor.fetchone()[0]
        assert executions == 2
        cursor.execute("SELECT COUNT(*) FROM provider_reconciliation_audit")
        audits = cursor.fetchone()[0]
        assert audits == 1
    return DurableEvidence(len(tasks), assets, plans, outbox, inbox, executions, audits)


if __name__ == "__main__":
    path = Path(sys.argv[1])
    config = ProcessConfig(**json.loads(path.read_text()))
    if len(sys.argv) > 2 and sys.argv[2] == "seed-other-owner":
        if not re.fullmatch(
            r"lux_process_backend_[0-9a-f]{12}", config.backend_database
        ):
            raise ValueError("Refusing to seed an unowned database")
        with (
            pymysql.connect(
                host=config.mysql_host,
                port=config.mysql_port,
                user="root",
                password=config.mysql_password,
                database=config.backend_database,
                autocommit=True,
            ) as connection,
            connection.cursor() as cursor,
        ):
            cursor.execute(
                "INSERT INTO `user` (id,display_name) VALUES (%s,%s)",
                (
                    "00000000-0000-0000-0000-000000000002",
                    "Other isolated fixture owner",
                ),
            )
    else:
        print(json.dumps(asdict(check(config))))
