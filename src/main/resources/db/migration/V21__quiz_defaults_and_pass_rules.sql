-- BL-025.13 teacher quiz defaults (term-scoped feature settings)
ALTER TABLE class_feature_settings
    ADD COLUMN default_quiz_question_count INT NOT NULL DEFAULT 5;
ALTER TABLE class_feature_settings
    ADD COLUMN default_quiz_pass_min_correct INT NULL;
ALTER TABLE class_feature_settings
    ADD COLUMN default_quiz_max_retries INT NULL;
ALTER TABLE class_feature_settings
    ADD COLUMN default_quiz_option_count INT NOT NULL DEFAULT 4;

-- BL-025.12 assignment quiz pass rules
ALTER TABLE assignments
    ADD COLUMN quiz_pass_min_correct INT NULL;
ALTER TABLE assignments
    ADD COLUMN quiz_max_retries INT NULL;
