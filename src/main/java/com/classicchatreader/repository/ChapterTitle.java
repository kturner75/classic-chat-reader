package com.classicchatreader.repository;

/** A chapter's id and title without its paragraphs, for listing the library. */
public record ChapterTitle(String bookId, String id, String title) {
}
