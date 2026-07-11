-- Classroom domain (BL-025.1 foundation). Portable DDL for H2, MariaDB/MySQL, PostgreSQL.
-- School is optional (class_sections.school_id nullable). Terms own rosters; invite_links are student enrollment codes.

CREATE TABLE schools (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_schools_slug UNIQUE (slug)
);

CREATE TABLE school_memberships (
    id VARCHAR(255) PRIMARY KEY,
    school_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT uk_sm_school_user_role UNIQUE (school_id, user_id, role),
    CONSTRAINT fk_sm_school FOREIGN KEY (school_id) REFERENCES schools (id),
    CONSTRAINT fk_sm_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_sm_user ON school_memberships (user_id);
CREATE INDEX idx_sm_school_status ON school_memberships (school_id, status);

CREATE TABLE class_sections (
    id VARCHAR(255) PRIMARY KEY,
    school_id VARCHAR(255) NULL,
    owner_user_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_cs_school FOREIGN KEY (school_id) REFERENCES schools (id),
    CONSTRAINT fk_cs_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
);

CREATE INDEX idx_cs_owner ON class_sections (owner_user_id);
CREATE INDEX idx_cs_school ON class_sections (school_id);

CREATE TABLE terms (
    id VARCHAR(255) PRIMARY KEY,
    class_section_id VARCHAR(255) NOT NULL,
    name VARCHAR(128) NOT NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    status VARCHAR(32) NOT NULL,
    retention_purge_after TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_terms_section FOREIGN KEY (class_section_id) REFERENCES class_sections (id)
);

CREATE INDEX idx_terms_section_status ON terms (class_section_id, status);

CREATE TABLE invite_links (
    id VARCHAR(255) PRIMARY KEY,
    term_id VARCHAR(255) NOT NULL,
    code_hash VARCHAR(120) NOT NULL,
    code_hint VARCHAR(12) NULL,
    label VARCHAR(128) NULL,
    max_uses INT NULL,
    use_count INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP NULL,
    revoked_at TIMESTAMP NULL,
    created_by_user_id VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    replaced_by_link_id VARCHAR(255) NULL,
    CONSTRAINT uk_invite_links_code_hash UNIQUE (code_hash),
    CONSTRAINT fk_il_term FOREIGN KEY (term_id) REFERENCES terms (id),
    CONSTRAINT fk_il_creator FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_il_term ON invite_links (term_id);

CREATE TABLE enrollments (
    id VARCHAR(255) PRIMARY KEY,
    term_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    joined_date DATE NOT NULL,
    left_date DATE NULL,
    invite_link_id VARCHAR(255) NULL,
    display_name_override VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_enrollments_term_user UNIQUE (term_id, user_id),
    CONSTRAINT fk_enr_term FOREIGN KEY (term_id) REFERENCES terms (id),
    CONSTRAINT fk_enr_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_enr_invite_link FOREIGN KEY (invite_link_id) REFERENCES invite_links (id)
);

CREATE INDEX idx_enr_user_status ON enrollments (user_id, status);
CREATE INDEX idx_enr_term_status ON enrollments (term_id, status);

CREATE TABLE class_role_memberships (
    id VARCHAR(255) PRIMARY KEY,
    term_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT uk_crm_term_user_role UNIQUE (term_id, user_id, role),
    CONSTRAINT fk_crm_term FOREIGN KEY (term_id) REFERENCES terms (id),
    CONSTRAINT fk_crm_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_crm_user_status ON class_role_memberships (user_id, status);
CREATE INDEX idx_crm_term_status ON class_role_memberships (term_id, status);

CREATE TABLE class_feature_settings (
    term_id VARCHAR(255) PRIMARY KEY,
    quiz_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    recap_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    tts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    illustration_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    character_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    chat_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    speed_reading_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reading_buddy_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP NOT NULL,
    updated_by_user_id VARCHAR(255) NULL,
    CONSTRAINT fk_cfs_term FOREIGN KEY (term_id) REFERENCES terms (id),
    CONSTRAINT fk_cfs_updater FOREIGN KEY (updated_by_user_id) REFERENCES users (id)
);

CREATE TABLE assignments (
    id VARCHAR(255) PRIMARY KEY,
    term_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    book_id VARCHAR(255) NOT NULL,
    chapter_id VARCHAR(255) NULL,
    chapter_index INT NULL,
    due_date DATE NULL,
    available_from_date DATE NULL,
    quiz_required BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_by_user_id VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_asg_term FOREIGN KEY (term_id) REFERENCES terms (id),
    CONSTRAINT fk_asg_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_asg_creator FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_asg_term_status ON assignments (term_id, status);

CREATE TABLE quiz_question_overrides (
    id VARCHAR(255) PRIMARY KEY,
    term_id VARCHAR(255) NOT NULL,
    book_id VARCHAR(255) NOT NULL,
    chapter_id VARCHAR(255) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    source_question_id VARCHAR(128) NULL,
    overlay_key VARCHAR(160) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    question_json TEXT NULL,
    status VARCHAR(32) NOT NULL,
    base_prompt_version VARCHAR(100) NULL,
    created_by_user_id VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL,
    notes VARCHAR(500) NULL,
    CONSTRAINT uk_qqo_term_chapter_overlay UNIQUE (term_id, chapter_id, overlay_key),
    CONSTRAINT fk_qqo_term FOREIGN KEY (term_id) REFERENCES terms (id),
    CONSTRAINT fk_qqo_creator FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_qqo_term_chapter ON quiz_question_overrides (term_id, chapter_id);

CREATE TABLE classroom_usage_events (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    term_id VARCHAR(255) NULL,
    class_section_id VARCHAR(255) NULL,
    school_id VARCHAR(255) NULL,
    event_type VARCHAR(64) NOT NULL,
    book_id VARCHAR(255) NULL,
    chapter_id VARCHAR(255) NULL,
    paragraph_index INT NULL,
    assignment_id VARCHAR(255) NULL,
    duration_ms BIGINT NULL,
    progress_percent INT NULL,
    session_id VARCHAR(255) NULL,
    idempotency_key VARCHAR(120) NULL,
    feature VARCHAR(64) NULL,
    provider VARCHAR(64) NULL,
    model_name VARCHAR(128) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    estimated_cost_micros BIGINT NULL,
    metadata_json TEXT NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_cue_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_cue_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_cue_term FOREIGN KEY (term_id) REFERENCES terms (id),
    CONSTRAINT fk_cue_section FOREIGN KEY (class_section_id) REFERENCES class_sections (id),
    CONSTRAINT fk_cue_school FOREIGN KEY (school_id) REFERENCES schools (id)
);

CREATE INDEX idx_cue_term_user_occurred ON classroom_usage_events (term_id, user_id, occurred_at);
CREATE INDEX idx_cue_user_occurred ON classroom_usage_events (user_id, occurred_at);
CREATE INDEX idx_cue_school_occurred ON classroom_usage_events (school_id, occurred_at);

CREATE TABLE education_record_access_logs (
    id VARCHAR(255) PRIMARY KEY,
    actor_user_id VARCHAR(255) NOT NULL,
    subject_user_id VARCHAR(255) NOT NULL,
    term_id VARCHAR(255) NULL,
    access_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NULL,
    resource_id VARCHAR(255) NULL,
    ip_hash VARCHAR(120) NULL,
    user_agent_hash VARCHAR(120) NULL,
    occurred_at TIMESTAMP NOT NULL,
    retain_until TIMESTAMP NULL,
    CONSTRAINT fk_eral_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT fk_eral_subject FOREIGN KEY (subject_user_id) REFERENCES users (id),
    CONSTRAINT fk_eral_term FOREIGN KEY (term_id) REFERENCES terms (id)
);

CREATE INDEX idx_eral_actor_occurred ON education_record_access_logs (actor_user_id, occurred_at);
CREATE INDEX idx_eral_subject_occurred ON education_record_access_logs (subject_user_id, occurred_at);

CREATE TABLE chat_export_jobs (
    id VARCHAR(255) PRIMARY KEY,
    requester_user_id VARCHAR(255) NOT NULL,
    subject_user_id VARCHAR(255) NOT NULL,
    term_id VARCHAR(255) NULL,
    format VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    chat_sources VARCHAR(128) NOT NULL,
    filter_book_id VARCHAR(255) NULL,
    filter_from TIMESTAMP NULL,
    filter_to TIMESTAMP NULL,
    artifact_storage_key VARCHAR(512) NULL,
    error_message VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_cej_requester FOREIGN KEY (requester_user_id) REFERENCES users (id),
    CONSTRAINT fk_cej_subject FOREIGN KEY (subject_user_id) REFERENCES users (id),
    CONSTRAINT fk_cej_term FOREIGN KEY (term_id) REFERENCES terms (id)
);

CREATE INDEX idx_cej_requester ON chat_export_jobs (requester_user_id, created_at);
CREATE INDEX idx_cej_subject ON chat_export_jobs (subject_user_id, created_at);
