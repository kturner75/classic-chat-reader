package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CharacterPrefetchServiceTest {

    private static final String BOOK_ID = "book-1063";

    @Mock private BookRepository bookRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private ParagraphRepository paragraphRepository;
    @Mock private CharacterService characterService;
    @Mock private LlmProvider reasoningProvider;

    private CharacterPrefetchService service;
    private BookEntity book;

    @BeforeEach
    void setUp() {
        service = new CharacterPrefetchService(
                bookRepository, chapterRepository, characterRepository,
                paragraphRepository, characterService, reasoningProvider);
        ReflectionTestUtils.setField(service, "cacheOnly", false);

        book = new BookEntity();
        book.setId(BOOK_ID);
        book.setTitle("The Cask of Amontillado");
        book.setAuthor("Edgar Allan Poe");
        book.setCharacterPrefetchCompleted(false);

        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.getProviderName()).thenReturn("xai");
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex(any())).thenReturn(List.of());

        ChapterEntity chapter = new ChapterEntity();
        chapter.setId("chapter-0");
        chapter.setChapterIndex(0);
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(chapter));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID)).thenReturn(List.of(chapter));
    }

    @Test
    void prefetch_usesConfiguredReasoningProvider() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name": "Montresor", "description": "The narrator.", "firstChapterNumber": 1}]
                """);
        when(characterService.upsertCharacter(any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    CharacterEntity saved = new CharacterEntity();
                    saved.setId("character-1");
                    saved.setName(invocation.getArgument(2));
                    return new CharacterService.CharacterUpsert(saved, true, false);
                });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(reasoningProvider).generate(any(), any());
        verify(characterService).upsertCharacter(any(), any(), eq("Montresor"), any(), anyInt(), eq(CharacterType.PRIMARY));
        verify(characterService).queuePortraitGeneration("character-1");
        assertThat(book.getCharacterPrefetchCompleted()).isTrue();
    }

    @Test
    void prefetch_doesNotLatchCompletedWhenProviderUnavailable() {
        when(reasoningProvider.isAvailable()).thenReturn(false);

        service.prefetchCharactersForBook(BOOK_ID);

        verify(reasoningProvider, never()).generate(any(), any());
        verify(bookRepository, never()).save(any());
        assertThat(book.getCharacterPrefetchCompleted()).isFalse();
    }

    @Test
    void prefetch_doesNotLatchCompletedWhenProviderThrows() {
        when(reasoningProvider.generate(any(), any()))
                .thenThrow(new IllegalStateException("provider down"));

        service.prefetchCharactersForBook(BOOK_ID);

        verify(bookRepository, never()).save(any());
        assertThat(book.getCharacterPrefetchCompleted()).isFalse();
    }

    @Test
    void prefetch_doesNotLatchCompletedOnUnusableResponse() {
        when(reasoningProvider.generate(any(), any())).thenReturn("Sorry, I cannot help with that.");

        service.prefetchCharactersForBook(BOOK_ID);

        verify(bookRepository, never()).save(any());
        assertThat(book.getCharacterPrefetchCompleted()).isFalse();
    }

    @Test
    void prefetch_latchesCompletedWhenModelDoesNotKnowTheBook() {
        when(reasoningProvider.generate(any(), any())).thenReturn("[]");

        service.prefetchCharactersForBook(BOOK_ID);

        verify(bookRepository).save(book);
        assertThat(book.getCharacterPrefetchCompleted()).isTrue();
    }

    @Test
    void prefetch_promotesExistingSecondaryToPrimary() {
        CharacterEntity existing = new CharacterEntity();
        existing.setId("character-fortunato");
        existing.setName("Fortunato");
        existing.setDescription("A wine connoisseur.");
        existing.setCharacterType(CharacterType.SECONDARY);

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name": "Fortunato", "description": "The insulted connoisseur who follows Montresor into the vaults.", "firstChapterNumber": 1}]
                """);
        when(characterService.upsertCharacter(any(), any(), eq("Fortunato"), any(), anyInt(), eq(CharacterType.PRIMARY)))
                .thenReturn(new CharacterService.CharacterUpsert(existing, false, true));

        service.prefetchCharactersForBook(BOOK_ID);

        verify(characterService, never()).queuePortraitGeneration(any());
        assertThat(book.getCharacterPrefetchCompleted()).isTrue();
    }

    @Test
    void prefetch_dedupesNormalizedNamesFromTheModelBeforeUpsert() {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId("chapter-0");
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(chapter));
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name": "Sally", "description": "A sister.", "firstChapterNumber": 1},
                  {"name": "Sally.", "description": "The same sister.", "firstChapterNumber": 1},
                  {"name": "  sally  ", "description": "Again.", "firstChapterNumber": 1}
                ]
                """);
        when(characterService.upsertCharacter(any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    CharacterEntity saved = new CharacterEntity();
                    saved.setId("character-sally");
                    saved.setName(invocation.getArgument(2));
                    return new CharacterService.CharacterUpsert(saved, true, false);
                });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(characterService).upsertCharacter(any(), any(), eq("Sally"), any(), anyInt(), eq(CharacterType.PRIMARY));
        verify(characterService).queuePortraitGeneration("character-sally");
    }

    @Test
    void prefetch_doesNotCollapseDistinctAllensOrBennets() {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId("chapter-0");
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(chapter));
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name": "Mr. Allen", "description": "A Bath acquaintance.", "firstChapterNumber": 1},
                  {"name": "Mrs. Allen", "description": "His wife.", "firstChapterNumber": 1},
                  {"name": "Mrs. Bennet", "description": "A mother.", "firstChapterNumber": 1},
                  {"name": "Elizabeth Bennet", "description": "Her daughter.", "firstChapterNumber": 1}
                ]
                """);
        when(characterService.upsertCharacter(any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    CharacterEntity saved = new CharacterEntity();
                    saved.setId("character-" + invocation.getArgument(2));
                    saved.setName(invocation.getArgument(2));
                    return new CharacterService.CharacterUpsert(saved, true, false);
                });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(characterService).upsertCharacter(any(), any(), eq("Mr. Allen"), any(), anyInt(), eq(CharacterType.PRIMARY));
        verify(characterService).upsertCharacter(any(), any(), eq("Mrs. Allen"), any(), anyInt(), eq(CharacterType.PRIMARY));
        verify(characterService).upsertCharacter(any(), any(), eq("Mrs. Bennet"), any(), anyInt(), eq(CharacterType.PRIMARY));
        verify(characterService).upsertCharacter(any(), any(), eq("Elizabeth Bennet"), any(), anyInt(), eq(CharacterType.PRIMARY));
    }
}
