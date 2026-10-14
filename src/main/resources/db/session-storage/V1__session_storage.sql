-- Fresh-schema activation only. Historical migrations and existing databases are not reset.
CREATE TABLE `user` (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE http_request (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response JSON NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, request_id),
    FOREIGN KEY (user_id) REFERENCES `user` (id)
) ENGINE=InnoDB;

CREATE TABLE `session` (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(255) NOT NULL,
    next_item_seq BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY session_owner (user_id, id),
    KEY session_listing (user_id, created_at, id),
    FOREIGN KEY (user_id) REFERENCES `user` (id),
    CHECK (next_item_seq >= 0)
) ENGINE=InnoDB;

CREATE TABLE agent_run (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_index BIGINT NOT NULL,
    source_revision BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    final_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY run_owner (user_id, id),
    UNIQUE KEY run_session (user_id, session_id, id),
    UNIQUE KEY run_order (session_id, run_index),
    KEY run_observation (last_seen_at),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id),
    CHECK (run_index > 0 AND source_revision >= 0),
    CHECK (COALESCE((source_revision = 0 AND status IS NULL AND started_at IS NULL AND finished_at IS NULL AND final_item_id IS NULL AND error_code IS NULL AND error_message IS NULL)
        OR (source_revision > 0 AND status IN ('RUNNING','COMPLETED','FAILED','CANCELLED')
            AND ((status = 'RUNNING' AND finished_at IS NULL) OR (status IN ('COMPLETED','FAILED','CANCELLED') AND finished_at IS NOT NULL))
            AND (status <> 'COMPLETED' OR final_item_id IS NOT NULL)), FALSE) = TRUE)
) ENGINE=InnoDB;

CREATE TABLE agent_turn (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    turn_index BIGINT NOT NULL,
    source_revision BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY turn_order (run_id, turn_index),
    UNIQUE KEY turn_reference (user_id, session_id, run_id, id),
    FOREIGN KEY (user_id, session_id, run_id) REFERENCES agent_run (user_id, session_id, id),
    CHECK (turn_index > 0 AND source_revision >= 0),
    CHECK (COALESCE((source_revision = 0 AND status IS NULL AND started_at IS NULL AND finished_at IS NULL AND error_code IS NULL AND error_message IS NULL)
        OR (source_revision > 0 AND status IN ('PREPARED','RUNNING','INTERRUPTED','COMPLETED','FAILED','CANCELLED')
            AND ((status IN ('PREPARED','RUNNING','INTERRUPTED') AND finished_at IS NULL) OR (status IN ('COMPLETED','FAILED','CANCELLED') AND finished_at IS NOT NULL))
            AND (status <> 'CANCELLED' OR started_at IS NULL)), FALSE) = TRUE)
) ENGINE=InnoDB;

CREATE TABLE task (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_event_seq BIGINT NOT NULL DEFAULT 0,
    result_sources JSON NULL,
    error_stage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    finished_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY task_owner (user_id, id),
    UNIQUE KEY task_session_reference (user_id, session_id, id),
    UNIQUE KEY task_operation (user_id, operation_id),
    KEY task_session (user_id, session_id, created_at, id),
    KEY task_observation (status, updated_at),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id),
    CHECK (file_type IN ('IMAGE','VIDEO') AND provider_event_seq >= 0),
    CHECK (status IN ('CREATED','SUBMITTED','RUNNING','IMPORTING','SUCCEEDED','FAILED')),
    CHECK ((status IN ('SUCCEEDED','FAILED')) = (finished_at IS NOT NULL)),
    CHECK (COALESCE((status = 'FAILED' AND error_stage IN ('GENERATION','IMPORT')) OR (status <> 'FAILED' AND error_stage IS NULL AND error_code IS NULL AND error_message IS NULL), FALSE) = TRUE)
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
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY asset_owner (user_id, id),
    UNIQUE KEY asset_operation (user_id, operation_id),
    UNIQUE KEY asset_source (task_id, source_index),
    FOREIGN KEY (user_id) REFERENCES `user` (id),
    FOREIGN KEY (user_id, task_id) REFERENCES task (user_id, id),
    CHECK (size_bytes >= 0),
    CHECK (COALESCE((task_id IS NULL AND source_index IS NULL AND operation_id IS NOT NULL)
        OR (task_id IS NOT NULL AND source_index IS NOT NULL AND source_index >= 0 AND operation_id IS NULL), FALSE) = TRUE)
) ENGINE=InnoDB;

CREATE TABLE item (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    item_index BIGINT NULL,
    source_revision BIGINT NOT NULL DEFAULT 0,
    input_sequence_no BIGINT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    call_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    tool_name VARCHAR(255) NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payload JSON NULL,
    completed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY item_owner (user_id, id),
    UNIQUE KEY item_session (user_id, session_id, id),
    UNIQUE KEY item_run_reference (user_id, session_id, run_id, id),
    UNIQUE KEY item_turn_reference (user_id, session_id, run_id, turn_id, id),
    UNIQUE KEY item_registration (session_id, sequence_no),
    UNIQUE KEY item_order (turn_id, item_index),
    UNIQUE KEY item_input (session_id, input_sequence_no),
    UNIQUE KEY item_call (turn_id, kind, call_id),
    UNIQUE KEY item_task_notification (user_id, task_id),
    KEY item_session_page (user_id, session_id, sequence_no),
    KEY item_input_page (user_id, session_id, input_sequence_no),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id),
    FOREIGN KEY (user_id, session_id, run_id, turn_id) REFERENCES agent_turn (user_id, session_id, run_id, id),
    FOREIGN KEY (user_id, session_id, task_id) REFERENCES task (user_id, session_id, id),
    CHECK (sequence_no > 0 AND source_revision >= 0),
    CHECK (COALESCE((source IN ('USER','SYSTEM') AND run_id IS NULL AND turn_id IS NULL AND item_index IS NULL
        AND input_sequence_no > 0 AND source_revision = 0 AND status = 'COMPLETED' AND payload IS NOT NULL AND completed_at IS NOT NULL
        AND call_id IS NULL AND tool_name IS NULL
        AND ((kind = 'MESSAGE' AND task_id IS NULL) OR (source = 'SYSTEM' AND kind = 'TASK_EVENT' AND task_id IS NOT NULL)))
        OR (source = 'AGENT' AND run_id IS NOT NULL AND turn_id IS NOT NULL AND item_index > 0 AND input_sequence_no IS NULL AND task_id IS NULL
            AND ((source_revision = 0 AND kind IS NULL AND status IS NULL AND payload IS NULL AND completed_at IS NULL AND call_id IS NULL AND tool_name IS NULL)
                OR (source_revision > 0 AND kind IN ('MESSAGE','REASONING_SUMMARY','TOOL_CALL','TOOL_RESULT') AND status IN ('PARTIAL','COMPLETED','INTERRUPTED') AND payload IS NOT NULL
                    AND ((status = 'COMPLETED' AND completed_at IS NOT NULL) OR (status <> 'COMPLETED' AND completed_at IS NULL))
                    AND ((kind IN ('MESSAGE','REASONING_SUMMARY') AND call_id IS NULL AND tool_name IS NULL)
                        OR (kind = 'TOOL_CALL' AND status = 'COMPLETED' AND CHAR_LENGTH(call_id) > 0 AND tool_name IS NOT NULL)
                        OR (kind = 'TOOL_RESULT' AND status = 'COMPLETED' AND CHAR_LENGTH(call_id) > 0 AND tool_name IS NULL))
                    AND (status <> 'PARTIAL' OR (source_revision = 1 AND kind IN ('MESSAGE','REASONING_SUMMARY')))))), FALSE) = TRUE)
) ENGINE=InnoDB;

ALTER TABLE agent_run ADD CONSTRAINT run_final_item
    FOREIGN KEY (user_id, session_id, id, final_item_id) REFERENCES item (user_id, session_id, run_id, id);

CREATE TABLE run_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sequence_no BIGINT NOT NULL,
    stream_seq BIGINT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY execution_event_order (session_id, sequence_no),
    UNIQUE KEY execution_stream_position (item_id, stream_seq),
    KEY execution_session (user_id, session_id, sequence_no),
    KEY execution_run (run_id, sequence_no),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id),
    CHECK (sequence_no > 0),
    CHECK (COALESCE((event_type = 'ITEM_TEXT_DELTA' AND item_id IS NOT NULL AND stream_seq > 0)
        OR (event_type = 'AGENT_ITEM_SNAPSHOT' AND item_id IS NOT NULL AND stream_seq IS NULL)
        OR (event_type IN ('RUN_SNAPSHOT','TURN_SNAPSHOT','MODEL_RESPONSE_COMPLETED') AND item_id IS NULL AND stream_seq IS NULL), FALSE) = TRUE)
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
    version BIGINT NOT NULL DEFAULT 1,
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
    event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    envelope JSON NOT NULL,
    conflict_envelope JSON NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RECEIVED',
    error_message VARCHAR(1000) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 1,
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
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id),
    CHECK (sequence_no > 0)
) ENGINE=InnoDB;
