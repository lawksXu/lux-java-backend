"""Read-only evidence from the Agent's owned SQLite store and checkpoints."""

import argparse
import hashlib
import json
import sqlite3
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class CheckpointRow:
    thread_id: str
    checkpoint_ns: str
    checkpoint_id: str
    checkpoint_sha256: str
    metadata_sha256: str


@dataclass(frozen=True)
class CheckpointEvidence:
    rows: tuple[CheckpointRow, ...]

    def require_retained(self, after: "CheckpointEvidence") -> None:
        if not self.rows:
            raise AssertionError("No checkpoint rows existed before Agent restart")
        retained = set(after.rows)
        if any(row not in retained for row in self.rows):
            raise AssertionError("Agent restart lost or changed an existing checkpoint")


@dataclass(frozen=True)
class CheckpointRetentionEvidence:
    before: CheckpointEvidence
    after: CheckpointEvidence
    retained: bool


@dataclass(frozen=True)
class ModelStoreEvidence:
    audit_count: int
    response_id: str | None
    requests_omit_embedded_images: bool


def readonly(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)


def blob_hash(value: bytes | str | None) -> str:
    payload = value.encode() if isinstance(value, str) else value or b""
    return hashlib.sha256(payload).hexdigest()


def checkpoints(store: Path) -> CheckpointEvidence:
    with readonly(Path(str(store) + ".checkpoints")) as database:
        values = database.execute(
            "SELECT thread_id,checkpoint_ns,checkpoint_id,checkpoint,metadata "
            "FROM checkpoints ORDER BY thread_id,checkpoint_ns,checkpoint_id"
        ).fetchall()
    return CheckpointEvidence(
        tuple(
            CheckpointRow(*row[:3], blob_hash(row[3]), blob_hash(row[4]))
            for row in values
        )
    )


def model_store(store: Path, execution_id: str) -> ModelStoreEvidence:
    with readonly(store) as database:
        audit_count = database.execute(
            "SELECT COUNT(*) FROM model_reconciliation_audit"
        ).fetchone()[0]
        response = database.execute(
            "SELECT response_id FROM model_provider_receipt WHERE execution_id=?",
            (execution_id,),
        ).fetchone()
        snapshots = database.execute(
            "SELECT request_snapshot FROM execution_receipt WHERE kind='MODEL'"
        ).fetchall()
    return ModelStoreEvidence(
        audit_count,
        response[0] if response else None,
        all("data:image/" not in row[0] for row in snapshots),
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("checkpoints", "model"))
    parser.add_argument("--store", type=Path, required=True)
    parser.add_argument("--execution-id")
    options = parser.parse_args()
    if options.mode == "model":
        if not options.execution_id:
            parser.error("model requires --execution-id")
        result = model_store(options.store, options.execution_id)
    else:
        result = checkpoints(options.store)
    print(json.dumps(asdict(result)))
