-- Durable account-level capabilities. These authorize global product actions such as
-- creating a classroom; contextual classroom roles remain in class_role_memberships.

CREATE TABLE account_capabilities (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    capability VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    granted_by_user_id VARCHAR(255) NULL,
    granted_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT uk_account_capability_user_capability UNIQUE (user_id, capability),
    CONSTRAINT fk_account_capability_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_account_capability_grantor FOREIGN KEY (granted_by_user_id) REFERENCES users (id)
);

CREATE INDEX idx_account_capability_user_status
    ON account_capabilities (user_id, status);
