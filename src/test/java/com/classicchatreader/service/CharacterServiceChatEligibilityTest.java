package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterServiceChatEligibilityTest {

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
    private ChapterEntity chapter;

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

        book = new BookEntity();
        book.setId("book-84");
        book.setTitle("Frankenstein");
        chapter = new ChapterEntity();
        chapter.setId("chapter-1");
        chapter.setChapterIndex(0);
        chapter.setTitle("Letter I");
        chapter.setBook(book);
    }

    @Test
    void primaryIsChatEligible() {
        CharacterEntity primary = character("Victor Frankenstein", CharacterType.PRIMARY);

        assertThat(service.isChatEligible(primary)).isTrue();
        assertThat(service.toChatAwareInfo(primary).chatEligible()).isTrue();
    }

    @Test
    void secondaryIsNeverChatEligibleEvenWhenBookHasNoPrimary() {
        CharacterEntity secondary = character("Justine Moritz", CharacterType.SECONDARY);

        assertThat(service.isChatEligible(secondary)).isFalse();
        assertThat(service.toChatAwareInfo(secondary).chatEligible()).isFalse();
        verify(characterRepository, never()).countByBookIdAndCharacterType(any(), any());
    }

    @Test
    void emptyPrimaryMeansNobodyToCall() {
        CharacterEntity secondary = character("The Moon", CharacterType.SECONDARY);
        when(characterRepository.findByBookIdOrderByCreatedAt("book-84"))
                .thenReturn(List.of(secondary));

        List<CharacterInfo> roster = service.getCharactersForBook("book-84");

        assertThat(roster).hasSize(1);
        assertThat(roster.getFirst().characterType()).isEqualTo("SECONDARY");
        assertThat(roster.getFirst().chatEligible()).isFalse();
        assertThat(service.isChatEligible(secondary)).isFalse();
    }

    private CharacterEntity character(String name, CharacterType type) {
        CharacterEntity entity = new CharacterEntity(book, name, "A character", chapter, 0, type);
        entity.setId("character-" + name.replace(' ', '-').toLowerCase());
        entity.setStatus(com.classicchatreader.entity.CharacterStatus.COMPLETED);
        return entity;
    }
}
