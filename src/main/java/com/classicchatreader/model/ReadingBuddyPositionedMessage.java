package com.classicchatreader.model;

/**
 * Lightweight message snapshot for prompt assembly (position-filtered injection).
 * Does not require DB entities; later chat/memory services can map into this shape.
 */
public record ReadingBuddyPositionedMessage(
        String role,
        String content,
        String kind,
        int chapterIndex,
        int paragraphIndex
) {
}
