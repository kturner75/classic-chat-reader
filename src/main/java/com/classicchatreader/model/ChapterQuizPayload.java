package com.classicchatreader.model;

import java.util.List;

public record ChapterQuizPayload(
        List<Question> questions
) {
    public record Question(
            String question,
            List<String> options,
            Integer correctOptionIndex,
            Integer citationParagraphIndex,
            String citationSnippet
    ) {
    }
}
