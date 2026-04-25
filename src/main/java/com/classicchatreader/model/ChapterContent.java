package com.classicchatreader.model;

import java.util.List;

public record ChapterContent(
    String bookId,
    String chapterId,
    String title,
    List<Paragraph> paragraphs
) {}
