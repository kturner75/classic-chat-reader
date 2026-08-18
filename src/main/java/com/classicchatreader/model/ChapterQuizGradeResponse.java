package com.classicchatreader.model;

import java.util.List;

public record ChapterQuizGradeResponse(
        String bookId,
        String chapterId,
        int totalQuestions,
        int correctAnswers,
        int scorePercent,
        int difficultyLevel,
        List<QuizTrophy> unlockedTrophies,
        QuizProgress progress,
        List<QuestionResult> results
) {
    public record QuestionResult(
            int questionIndex,
            String question,
            int selectedOptionIndex,
            int correctOptionIndex,
            boolean correct,
            String correctAnswer,
            Integer citationParagraphIndex,
            String citationSnippet
    ) {
        public QuestionResult withoutAnswerKey() {
            return new QuestionResult(
                    questionIndex,
                    question,
                    selectedOptionIndex,
                    -1,
                    correct,
                    null,
                    null,
                    null
            );
        }
    }

    /** Strip the answer key so a retrying student cannot read it from the grade payload. */
    public ChapterQuizGradeResponse withoutAnswerKey() {
        if (results == null || results.isEmpty()) {
            return this;
        }
        return new ChapterQuizGradeResponse(
                bookId,
                chapterId,
                totalQuestions,
                correctAnswers,
                scorePercent,
                difficultyLevel,
                unlockedTrophies,
                progress,
                results.stream().map(QuestionResult::withoutAnswerKey).toList()
        );
    }
}
