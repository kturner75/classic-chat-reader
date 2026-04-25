package com.classicchatreader.model;

public record QuizProgress(
        long totalAttempts,
        long perfectAttempts,
        int currentPerfectStreak
) {
}
