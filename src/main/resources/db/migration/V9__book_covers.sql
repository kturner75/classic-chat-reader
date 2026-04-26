CREATE TABLE book_covers (
    id VARCHAR(255) PRIMARY KEY,
    book_id VARCHAR(255) NOT NULL,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    error_message VARCHAR(1000),
    generated_prompt VARCHAR(2000),
    image_filename VARCHAR(255),
    lease_expires_at TIMESTAMP,
    lease_owner VARCHAR(120),
    next_retry_at TIMESTAMP,
    retry_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(64) NOT NULL,
    CONSTRAINT uk_book_covers_book UNIQUE (book_id),
    CONSTRAINT fk_book_covers_book FOREIGN KEY (book_id) REFERENCES books (id)
);

CREATE INDEX idx_book_covers_status ON book_covers (status);
