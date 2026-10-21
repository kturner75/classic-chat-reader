package com.classicchatreader.model;

/** A paragraph's chapter, position and text, without its entity: what the search index needs. */
public record ParagraphText(String chapterId, int paragraphIndex, String content) {
}
