-- Track when assignment quiz pass rules became active for attempt-window scoping.
ALTER TABLE assignments
    ADD COLUMN quiz_rules_activated_at TIMESTAMP NULL;
