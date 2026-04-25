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
    }
}
