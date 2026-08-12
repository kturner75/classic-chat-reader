-- BL-025.10 / BL-025.4: durable first-open signal for teacher→student overview.
CREATE TABLE assignment_progress (
    id VARCHAR(255) PRIMARY KEY,
    term_id VARCHAR(255) NOT NULL,
    assignment_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    first_opened_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ap_assignment_user UNIQUE (assignment_id, user_id),
    CONSTRAINT fk_ap_term FOREIGN KEY (term_id) REFERENCES terms (id),
    CONSTRAINT fk_ap_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id),
    CONSTRAINT fk_ap_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_ap_term_user ON assignment_progress (term_id, user_id);
CREATE INDEX idx_ap_assignment ON assignment_progress (assignment_id);
