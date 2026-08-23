CREATE TABLE pending_external_identity_links (
    id VARCHAR(255) PRIMARY KEY,
    token_hash VARCHAR(120) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    email VARCHAR(320) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_pending_external_identity_links_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_pending_external_identity_links_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_pending_external_identity_links_user
    ON pending_external_identity_links (user_id);

CREATE INDEX idx_pending_external_identity_links_expires
    ON pending_external_identity_links (expires_at);
