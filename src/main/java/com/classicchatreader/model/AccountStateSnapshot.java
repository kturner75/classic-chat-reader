package com.classicchatreader.model;

import java.util.List;
import java.util.Map;

public record AccountStateSnapshot(
        List<String> favoriteBookIds,
        Map<String, BookActivity> bookActivity,
        ReaderPreferences readerPreferences,
        Map<String, Boolean> recapOptOut
) {

    public static AccountStateSnapshot empty() {
        return new AccountStateSnapshot(List.of(), Map.of(), null, Map.of());
    }

    public record BookActivity(
            Integer chapterCount,
            Integer lastChapterIndex,
            Integer lastPage,
            Integer totalPages,
            Double progressRatio,
            Double maxProgressRatio,
            Boolean completed,
            Integer openCount,
            String lastOpenedAt,
            String lastReadAt,
            String completedAt
    ) {
    }

    public record ReaderPreferences(
            Double fontSize,
            Double lineHeight,
            Double columnGap,
            String theme,
            String updatedAt
    ) {
    }
}
