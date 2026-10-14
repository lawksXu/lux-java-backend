-- Reusable plans extend the active Session schema without rewriting historical rows.
CREATE TABLE generation_plan (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    configuration JSON NOT NULL,
    frozen_request JSON NULL,
    frozen_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY plan_session_reference (user_id, session_id, id),
    KEY plan_listing (user_id, session_id, created_at, id),
    FOREIGN KEY (user_id, session_id) REFERENCES `session` (user_id, id),
    CHECK (revision > 0),
    CHECK (state IN ('DRAFT', 'FROZEN')),
    CHECK ((state = 'DRAFT' AND frozen_request IS NULL AND frozen_at IS NULL)
        OR (state = 'FROZEN' AND frozen_request IS NOT NULL AND frozen_at IS NOT NULL))
) ENGINE=InnoDB;

ALTER TABLE `session`
    ADD active_plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD plan_focus_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT session_plan_focus FOREIGN KEY (user_id, id, active_plan_id)
        REFERENCES generation_plan (user_id, session_id, id),
    ADD CHECK (plan_focus_revision >= 0);

ALTER TABLE task
    ADD plan_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT task_generation_plan FOREIGN KEY (user_id, session_id, plan_id)
        REFERENCES generation_plan (user_id, session_id, id);

ALTER TABLE asset ADD verified_media_metadata JSON NULL;
