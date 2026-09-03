package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.model.CharacterInfo;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterServicePatchTest {

    @Mock private CharacterRepository characterRepository;
    @Mock private ChapterAnalysisRepository chapterAnalysisRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private BookRepository bookRepository;
    @Mock private ParagraphRepository paragraphRepository;
    @Mock private CharacterExtractionService extractionService;
    @Mock private CharacterPortraitService portraitService;
    @Mock private IllustrationService illustrationService;
    @Mock private ComfyUIService comfyUIService;
    @Mock private CharacterPortraitImageGeneratorService portraitImageGenerator;

    private CharacterService service;
    private BookEntity book;
    private ChapterEntity chapterOne;
    private ChapterEntity chapterSix;
    private CharacterEntity grandma;

    @BeforeEach
    void setUp() {
        service = new CharacterService(
                characterRepository,
                chapterAnalysisRepository,
                chapterRepository,
                bookRepository,
                paragraphRepository,
                extractionService,
                portraitService,
                illustrationService,
                comfyUIService,
                portraitImageGenerator,
                new AssetKeyService()
        );
        service.setSelf(service);

        book = new BookEntity("An Old-Fashioned Girl", "Louisa May Alcott", "gutenberg");
        book.setId("16b0e2d8-7877-4f42-a1a3-1118bf9ec305");
        book.setCharacterEnabled(true);

        chapterOne = chapter("chapter-1", 1, "Chapter I. Polly Arrives");
        chapterSix = chapter("chapter-6", 6, "Chapter VI. Grandma");

        grandma = new CharacterEntity(book, "Grandma", "Sydneys grandmother", chapterOne, 4, CharacterType.SECONDARY);
        grandma.setId("character-grandma");
        grandma.setStatus(CharacterStatus.COMPLETED);
    }

    @Test
    void patchCharacter_typeAndChapter_updatesBothAndDefaultsParagraph() {
        when(characterRepository.findByIdWithBookAndChapter("character-grandma"))
                .thenReturn(Optional.of(grandma));
        when(chapterRepository.findByBookIdAndChapterIndex(book.getId(), 6))
                .thenReturn(Optional.of(chapterSix));
        when(characterRepository.save(grandma)).thenReturn(grandma);

        assertThat(service.isChatEligible(grandma)).isFalse();

        CharacterInfo info = service.patchCharacter("character-grandma", "PRIMARY", 6, null).orElseThrow();

        assertThat(grandma.getCharacterType()).isEqualTo(CharacterType.PRIMARY);
        assertThat(grandma.getFirstChapter()).isSameAs(chapterSix);
        assertThat(grandma.getFirstParagraphIndex()).isZero();
        assertThat(info.characterType()).isEqualTo("PRIMARY");
        assertThat(info.firstChapterId()).isEqualTo("chapter-6");
        assertThat(info.firstChapterTitle()).isEqualTo("Chapter VI. Grandma");
        assertThat(info.firstChapterIndex()).isEqualTo(6);
        assertThat(info.firstParagraphIndex()).isZero();
        assertThat(info.chatEligible()).isTrue();
        verify(characterRepository).save(grandma);
    }

    @Test
    void patchCharacter_omittedFields_leaveTypeAndPlacementUnchanged() {
        grandma.setCharacterType(CharacterType.PRIMARY);
        when(characterRepository.findByIdWithBookAndChapter("character-grandma"))
                .thenReturn(Optional.of(grandma));

        CharacterInfo info = service.patchCharacter("character-grandma", null, null, null).orElseThrow();

        assertThat(grandma.getCharacterType()).isEqualTo(CharacterType.PRIMARY);
        assertThat(grandma.getFirstChapter()).isSameAs(chapterOne);
        assertThat(grandma.getFirstParagraphIndex()).isEqualTo(4);
        assertThat(info.characterType()).isEqualTo("PRIMARY");
        assertThat(info.firstChapterIndex()).isEqualTo(1);
        assertThat(info.firstParagraphIndex()).isEqualTo(4);
        assertThat(info.chatEligible()).isTrue();
        verify(chapterRepository, never()).findByBookIdAndChapterIndex(anyString(), anyInt());
        verify(characterRepository, never()).save(any());
    }

    @Test
    void patchCharacter_primaryToSecondary_isAllowedAndFlipsChatEligible() {
        grandma.setCharacterType(CharacterType.PRIMARY);
        when(characterRepository.findByIdWithBookAndChapter("character-grandma"))
                .thenReturn(Optional.of(grandma));
        when(characterRepository.save(grandma)).thenReturn(grandma);

        CharacterInfo info = service.patchCharacter("character-grandma", "SECONDARY", null, null).orElseThrow();

        assertThat(grandma.getCharacterType()).isEqualTo(CharacterType.SECONDARY);
        assertThat(grandma.getFirstChapter()).isSameAs(chapterOne);
        assertThat(grandma.getFirstParagraphIndex()).isEqualTo(4);
        assertThat(info.characterType()).isEqualTo("SECONDARY");
        assertThat(info.chatEligible()).isFalse();
        assertThat(service.isChatEligible(grandma)).isFalse();
    }

    @Test
    void patchCharacter_unknownChapterIndex_rejectsWithoutClearingFirstChapter() {
        when(characterRepository.findByIdWithBookAndChapter("character-grandma"))
                .thenReturn(Optional.of(grandma));
        when(chapterRepository.findByBookIdAndChapterIndex(book.getId(), 99))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.patchCharacter("character-grandma", "SECONDARY", 99, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown firstChapterIndex 99");

        assertThat(grandma.getCharacterType()).isEqualTo(CharacterType.SECONDARY);
        assertThat(grandma.getFirstChapter()).isSameAs(chapterOne);
        assertThat(grandma.getFirstChapter()).isNotNull();
        verify(characterRepository, never()).save(any());
    }

    @Test
    void patchCharacter_invalidType_rejectsWithoutWrite() {
        when(characterRepository.findByIdWithBookAndChapter("character-grandma"))
                .thenReturn(Optional.of(grandma));

        assertThatThrownBy(() -> service.patchCharacter("character-grandma", "SUPPORTING", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid characterType");

        assertThat(grandma.getCharacterType()).isEqualTo(CharacterType.SECONDARY);
        verify(characterRepository, never()).save(any());
    }

    @Test
    void patchCharacter_missingCharacter_returnsEmpty() {
        when(characterRepository.findByIdWithBookAndChapter("character-missing"))
                .thenReturn(Optional.empty());

        assertThat(service.patchCharacter("character-missing", "PRIMARY", 6, null)).isEmpty();
        verify(characterRepository, never()).save(any());
    }

    @Test
    void patchCharacter_cacheOnly_rejectsWithoutWrite() {
        ReflectionTestUtils.setField(service, "cacheOnly", true);

        assertThatThrownBy(() -> service.patchCharacter("character-grandma", "SECONDARY", 6, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache-only");

        verify(characterRepository, never()).findByIdWithBookAndChapter(anyString());
        verify(characterRepository, never()).save(any());
    }

    @Test
    void patchCharacter_negativeParagraph_rejectsWithoutWrite() {
        when(characterRepository.findByIdWithBookAndChapter("character-grandma"))
                .thenReturn(Optional.of(grandma));

        assertThatThrownBy(() -> service.patchCharacter("character-grandma", null, 6, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstParagraphIndex");

        verify(chapterRepository, never()).findByBookIdAndChapterIndex(anyString(), anyInt());
        verify(characterRepository, never()).save(any());
    }

    @Test
    void patchCharacter_chapterAndParagraph_keepsProvidedParagraph() {
        when(characterRepository.findByIdWithBookAndChapter("character-grandma"))
                .thenReturn(Optional.of(grandma));
        when(chapterRepository.findByBookIdAndChapterIndex(book.getId(), 6))
                .thenReturn(Optional.of(chapterSix));
        when(characterRepository.save(grandma)).thenReturn(grandma);

        CharacterInfo info = service.patchCharacter("character-grandma", null, 6, 2).orElseThrow();

        assertThat(grandma.getFirstChapter()).isSameAs(chapterSix);
        assertThat(grandma.getFirstParagraphIndex()).isEqualTo(2);
        assertThat(info.firstParagraphIndex()).isEqualTo(2);
        assertThat(info.characterType()).isEqualTo("SECONDARY");
        assertThat(info.chatEligible()).isFalse();
    }

    private ChapterEntity chapter(String id, int index, String title) {
        ChapterEntity chapter = new ChapterEntity(index, title);
        chapter.setId(id);
        chapter.setBook(book);
        return chapter;
    }
}
