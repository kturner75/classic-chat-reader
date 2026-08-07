package com.classicchatreader.model;

import java.util.List;

public record ChapterQuizViewPayload(
        List<Question> questions,
        String contentVersion
) {
    public ChapterQuizViewPayload(List<Question> questions) {
        this(questions, null);
    }

    public record Question(
            String id,
            String question,
            List<String> options
    ) {
    }
}
