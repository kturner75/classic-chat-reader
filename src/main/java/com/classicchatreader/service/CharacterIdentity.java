package com.classicchatreader.service;

import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses a surviving character when the same identity was persisted more than once.
 */
public final class CharacterIdentity {

    private CharacterIdentity() {
    }

    public static String keyOf(CharacterEntity character) {
        if (character == null) {
            return "";
        }
        if (character.getNameKey() != null && !character.getNameKey().isBlank()) {
            return character.getNameKey();
        }
        String key = CharacterNameNormalizer.identityKey(character.getName());
        return key.isBlank() ? CharacterNameNormalizer.fallbackKey(character.getId()) : key;
    }

    public static CharacterEntity prefer(CharacterEntity left, CharacterEntity right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return comparator().compare(left, right) <= 0 ? left : right;
    }

    public static List<CharacterEntity> dedupe(List<CharacterEntity> characters) {
        if (characters == null || characters.isEmpty()) {
            return List.of();
        }
        Map<String, CharacterEntity> winners = new LinkedHashMap<>();
        for (CharacterEntity character : characters) {
            String key = keyOf(character);
            winners.merge(key, character, CharacterIdentity::prefer);
        }
        return characters.stream()
                .filter(character -> character == winners.get(keyOf(character)))
                .toList();
    }

    public static Comparator<CharacterEntity> comparator() {
        return Comparator
                .comparing(CharacterIdentity::typeRank).reversed()
                .thenComparing(CharacterIdentity::statusRank).reversed()
                .thenComparing(CharacterIdentity::hasPortrait).reversed()
                .thenComparing(CharacterIdentity::firstChapterIndex)
                .thenComparing(CharacterEntity::getFirstParagraphIndex)
                .thenComparing(CharacterIdentity::createdAtOrMax)
                .thenComparing(character -> nullToEmpty(character.getId()));
    }

    private static int typeRank(CharacterEntity character) {
        return character.getCharacterType() == CharacterType.PRIMARY ? 1 : 0;
    }

    private static int statusRank(CharacterEntity character) {
        CharacterStatus status = character.getStatus();
        if (status == CharacterStatus.COMPLETED) {
            return 4;
        }
        if (status == CharacterStatus.GENERATING) {
            return 3;
        }
        if (status == CharacterStatus.PENDING) {
            return 2;
        }
        if (status == CharacterStatus.FAILED) {
            return 1;
        }
        return 0;
    }

    private static boolean hasPortrait(CharacterEntity character) {
        return character.getPortraitFilename() != null && !character.getPortraitFilename().isBlank();
    }

    private static int firstChapterIndex(CharacterEntity character) {
        if (character.getFirstChapter() == null) {
            return Integer.MAX_VALUE;
        }
        return character.getFirstChapter().getChapterIndex();
    }

    private static LocalDateTime createdAtOrMax(CharacterEntity character) {
        return character.getCreatedAt() == null ? LocalDateTime.MAX : character.getCreatedAt();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
