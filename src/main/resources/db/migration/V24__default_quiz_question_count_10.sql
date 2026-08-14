ALTER TABLE class_feature_settings
    ALTER COLUMN default_quiz_question_count SET DEFAULT 10;

-- Previous product default was 5; move existing classes to Jessica's 10-question default.
UPDATE class_feature_settings
SET default_quiz_question_count = 10
WHERE default_quiz_question_count = 5;
