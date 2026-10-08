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
    boolean chatEligible,
    String callVoice
) {
    /** Characters as the reader sees them: no call voice. */
    public CharacterInfo(
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
        this(id, name, description, firstChapterId, firstChapterTitle, firstChapterIndex,
            firstParagraphIndex, status, portraitReady, characterType, chatEligible, null);
    }

    /** The voice a call with this character uses, once one has been chosen for the current provider. */
    private static final String CALL_VOICE_PROVIDER = "xai";

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
            primary,
            CALL_VOICE_PROVIDER.equals(entity.getCallVoiceProvider()) ? entity.getCallVoice() : null
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
                chatEligible,
                callVoice
        );
    }
}
