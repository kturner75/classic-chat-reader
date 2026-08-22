package com.classicchatreader.service;

import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterIdentityTest {

    @Test
    void prefer_keepsPrimaryCompletedPortraitOverLaterDuplicates() {
        CharacterEntity pending = character("a", "Sally", CharacterType.SECONDARY, CharacterStatus.PENDING, null, 2);
        CharacterEntity completed = character("b", "sally", CharacterType.PRIMARY, CharacterStatus.COMPLETED, "portrait.png", 5);

        assertEquals(completed, CharacterIdentity.prefer(pending, completed));
    }

    @Test
    void dedupe_collapsesNormalizedNameVariantsAndPreservesEncounterOrder() {
        CharacterEntity first = character("a", "Sally", CharacterType.SECONDARY, CharacterStatus.PENDING, null, 1);
        CharacterEntity other = character("c", "Henry Tilney", CharacterType.PRIMARY, CharacterStatus.COMPLETED, "h.png", 2);
        CharacterEntity duplicate = character("b", "Sally.", CharacterType.SECONDARY, CharacterStatus.COMPLETED, "s.png", 8);

        List<CharacterEntity> deduped = CharacterIdentity.dedupe(List.of(first, other, duplicate));

        assertEquals(List.of(other, duplicate), deduped);
    }

    private static CharacterEntity character(String id, String name, CharacterType type,
                                             CharacterStatus status, String portrait, int paragraph) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId("chapter-1");
        chapter.setChapterIndex(0);
        CharacterEntity character = new CharacterEntity();
        character.setId(id);
        character.setName(name);
        character.setCharacterType(type);
        character.setStatus(status);
        character.setPortraitFilename(portrait);
        character.setFirstChapter(chapter);
        character.setFirstParagraphIndex(paragraph);
        character.setCreatedAt(LocalDateTime.of(2026, 8, 17, 12, paragraph));
        return character;
    }
}
