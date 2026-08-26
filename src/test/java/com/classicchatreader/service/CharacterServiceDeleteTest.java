package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterServiceDeleteTest {

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
        book.setCharacterPrefetchCompleted(true);
    }

    @Test
    void deleteCharactersForBook_clearsPrefetchLatch() {
        CharacterEntity existing = new CharacterEntity();
        existing.setName("The Moon");
        when(characterRepository.findByBookIdOrderByCreatedAt("book-84")).thenReturn(List.of(existing));
        when(bookRepository.findById("book-84")).thenReturn(Optional.of(book));

        int deleted = service.deleteCharactersForBook("book-84");

        assertThat(deleted).isEqualTo(1);
        assertThat(book.getCharacterPrefetchCompleted()).isFalse();
        verify(chapterAnalysisRepository).deleteByBookId("book-84");
        verify(characterRepository).deleteByBookId("book-84");
        verify(bookRepository).save(book);
    }

    @Test
    void deleteCharactersForBook_directedSlotDeletesStableCacheKey() {
        book.setSource("gutenberg");
        book.setSourceId("84");

        ChapterEntity chapter = new ChapterEntity();
        chapter.setId("chapter-1");
        chapter.setBook(book);
        chapter.setChapterIndex(1);

        CharacterEntity directed = new CharacterEntity(book, "The Creature", "Victor's creation", chapter, 0);
        directed.setId("character-1");
        directed.setStatus(CharacterStatus.PENDING);
        directed.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        directed.setPortraitPrompt("The Creature in candlelight");

        when(characterRepository.findByBookIdOrderByCreatedAt("book-84")).thenReturn(List.of(directed));
        when(bookRepository.findById("book-84")).thenReturn(Optional.of(book));
        when(comfyUIService.deletePortraitFile(
                "books/gutenberg/84/portraits/characters/the-creature.png")).thenReturn(true);

        int deleted = service.deleteCharactersForBook("book-84");

        assertThat(deleted).isEqualTo(1);
        verify(comfyUIService).deletePortraitFile("books/gutenberg/84/portraits/characters/the-creature.png");
        verify(comfyUIService, org.mockito.Mockito.never())
                .deletePortraitFile(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        verify(characterRepository).deleteByBookId("book-84");
    }
}
