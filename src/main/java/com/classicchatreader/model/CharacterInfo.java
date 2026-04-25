package com.classicchatreader.model;

public record CharacterInfo(
    String id,
    String name,
    String description,
    String firstChapterId,
    String firstChapterTitle,
    int firstChapterIndex,
    int firstParagraphIndex,
    String status,
    boolean portraitReady,
    String characterType
) {
    public static CharacterInfo from(com.classicchatreader.entity.CharacterEntity entity) {
        return new CharacterInfo(
            entity.getId(),
            entity.getName(),
            entity.getDescription(),
            entity.getFirstChapter().getId(),
            entity.getFirstChapter().getTitle(),
            entity.getFirstChapter().getChapterIndex(),
            entity.getFirstParagraphIndex(),
            entity.getStatus().name(),
            entity.getStatus() == com.classicchatreader.entity.CharacterStatus.COMPLETED,
            entity.getCharacterType().name()
        );
    }
}
