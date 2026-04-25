package com.classicchatreader.model;

import java.time.LocalDateTime;

public record BookmarkedParagraph(
        String chapterId,
        String chapterTitle,
        int paragraphIndex,
        String snippet,
        LocalDateTime updatedAt
) {
}
