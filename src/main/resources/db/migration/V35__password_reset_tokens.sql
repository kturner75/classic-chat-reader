CREATE TABLE password_reset_tokens (
    id VARCHAR(255) PRIMARY KEY,
    token_hash VARCHAR(120) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_password_reset_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_password_reset_tokens_user
    ON password_reset_tokens (user_id);

CREATE INDEX idx_password_reset_tokens_expires
    ON password_reset_tokens (expires_at);
