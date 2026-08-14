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
    String characterType,
    boolean chatEligible
) {
    public static CharacterInfo from(com.classicchatreader.entity.CharacterEntity entity) {
        boolean primary = entity.getCharacterType()
                == com.classicchatreader.entity.CharacterType.PRIMARY;
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
            entity.getCharacterType().name(),
            primary
        );
    }

    public CharacterInfo withChatEligible(boolean chatEligible) {
        return new CharacterInfo(
                id,
                name,
                description,
                firstChapterId,
                firstChapterTitle,
                firstChapterIndex,
                firstParagraphIndex,
                status,
                portraitReady,
                characterType,
                chatEligible
        );
    }
}
