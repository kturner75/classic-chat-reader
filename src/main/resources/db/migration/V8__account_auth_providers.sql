CREATE TABLE user_local_credentials (
    user_id VARCHAR(255) PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    login_locked_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_local_credentials_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

INSERT INTO user_local_credentials (
    user_id,
    password_hash,
    failed_login_attempts,
    login_locked_until,
    created_at,
    updated_at
)
SELECT
    id,
    password_hash,
    COALESCE(failed_login_attempts, 0),
    login_locked_until,
    created_at,
    updated_at
FROM users
WHERE password_hash IS NOT NULL;

ALTER TABLE users
    DROP COLUMN password_hash,
    DROP COLUMN failed_login_attempts,
    DROP COLUMN login_locked_until;

CREATE TABLE user_auth_identities (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    email VARCHAR(320) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_auth_identities_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_user_auth_identities_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uk_user_auth_identities_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX idx_user_auth_identities_user
    ON user_auth_identities (user_id);
