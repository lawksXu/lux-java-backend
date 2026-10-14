CREATE TABLE `user` (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE http_request (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    response JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, request_id),
    FOREIGN KEY (user_id) REFERENCES `user` (id)
) ENGINE=InnoDB;

CREATE TABLE `session` (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(255) NOT NULL,
    queue_paused BOOLEAN NOT NULL DEFAULT FALSE,
    active_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    next_message_seq BIGINT NOT NULL DEFAULT 0,
    next_event_seq BIGINT NOT NULL DEFAULT 0,
    task_update_seq BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY session_owner (user_id, id),
    KEY session_listing (user_id, created_at, id),
    FOREIGN KEY (user_id) REFERENCES `user` (id)
) ENGINE=InnoDB;

CREATE TABLE agent_run (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) NOT NULL,
    last_event_seq BIGINT NOT NULL DEFAULT 0,
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_reconcile_at DATETIME(6) NULL,
    error_message TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    UNIQUE KEY run_owner (user_id, id),
    UNIQUE KEY run_session (user_id, session_id, id),
    KEY run_recovery (status, last_seen_at),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id),
    CHECK (status IN ('PENDING','RUNNING','WAITING','CANCELLING','COMPLETED','FAILED','CANCELLED'))
) ENGINE=InnoDB;

ALTER TABLE `session` ADD CONSTRAINT session_active_run
    FOREIGN KEY (user_id, id, active_run_id) REFERENCES agent_run (user_id, session_id, id);

CREATE TABLE message (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    queue_status VARCHAR(16) NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    content JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY message_owner (user_id, id),
    UNIQUE KEY message_session (user_id, session_id, id),
    UNIQUE KEY message_order (session_id, sequence_no),
    UNIQUE KEY assistant_run (run_id),
    UNIQUE KEY message_request (user_id, client_request_id),
    KEY message_queue (user_id, session_id, queue_status, sequence_no),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id),
    FOREIGN KEY (user_id, session_id, run_id) REFERENCES agent_run (user_id, session_id, id),
    CHECK ((role = 'USER' AND run_id IS NULL AND client_request_id IS NOT NULL
            AND queue_status IN ('QUEUED','CLAIMED','WITHDRAWN'))
        OR (role = 'ASSISTANT' AND run_id IS NOT NULL AND client_request_id IS NULL AND queue_status IS NULL))
) ENGINE=InnoDB;

CREATE TABLE run_input (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (run_id, position),
    UNIQUE KEY input_once (message_id),
    FOREIGN KEY (user_id, session_id, run_id) REFERENCES agent_run (user_id, session_id, id),
    FOREIGN KEY (user_id, session_id, message_id) REFERENCES message (user_id, session_id, id)
) ENGINE=InnoDB;

CREATE TABLE run_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY run_event_order (run_id, sequence_no),
    FOREIGN KEY (user_id, run_id) REFERENCES agent_run (user_id, id)
) ENGINE=InnoDB;

CREATE TABLE task (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    origin_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool_call_id VARCHAR(255) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    kind VARCHAR(16) NOT NULL,
    request JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    provider_event_seq BIGINT NOT NULL DEFAULT 0,
    provider_task_id VARCHAR(255) NULL,
    result_sources JSON NULL,
    import_attempts INT NOT NULL DEFAULT 0,
    next_import_at DATETIME(6) NULL,
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_reconcile_at DATETIME(6) NULL,
    error_message TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY task_owner (user_id, id),
    UNIQUE KEY task_operation (user_id, operation_id),
    UNIQUE KEY task_tool (origin_run_id, tool_call_id),
    KEY task_import (status, next_import_at),
    KEY task_session (user_id, session_id, created_at),
    FOREIGN KEY (user_id, session_id, origin_run_id) REFERENCES agent_run (user_id, session_id, id),
    CHECK (kind IN ('IMAGE','VIDEO')),
    CHECK (status IN ('CREATED','SUBMITTED','RUNNING','IMPORTING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB;

CREATE TABLE asset (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_index INT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY asset_owner (user_id, id),
    UNIQUE KEY asset_operation (user_id, operation_id),
    UNIQUE KEY asset_source (task_id, source_index),
    FOREIGN KEY (user_id) REFERENCES `user` (id),
    FOREIGN KEY (user_id, task_id) REFERENCES task (user_id, id),
    CHECK (size_bytes >= 0)
) ENGINE=InnoDB;

CREATE TABLE outbox_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(128) NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    envelope JSON NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    delivered_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY outbox_identity (event_id),
    KEY outbox_pending (delivered_at, next_attempt_at, id),
    KEY outbox_partition (topic, partition_key, delivered_at, id),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id)
) ENGINE=InnoDB;

CREATE TABLE inbox_event (
    source VARCHAR(128) NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    event_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    envelope JSON NOT NULL,
    conflict_envelope JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    error_message VARCHAR(1000) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6) NULL,
    PRIMARY KEY (source, event_id),
    KEY inbox_pending (status, next_attempt_at, received_at),
    CHECK (status IN ('RECEIVED','APPLIED','REJECTED'))
) ENGINE=InnoDB;

CREATE TABLE session_event (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id, sequence_no),
    KEY session_event_owner (user_id, session_id, sequence_no),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id)
) ENGINE=InnoDB;
