package com.classicchatreader.model;

import java.time.LocalDateTime;

public record QuizTrophy(
        String code,
        String title,
        String description,
        LocalDateTime unlockedAt
) {
}
