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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Montresor"))
                .thenReturn(Optional.empty());
        when(characterRepository.save(any())).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("character-1");
            return saved;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(reasoningProvider).generate(any(), any());
        verify(characterRepository).save(any(CharacterEntity.class));
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
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Fortunato"))
                .thenReturn(Optional.of(existing));

        service.prefetchCharactersForBook(BOOK_ID);

        assertThat(existing.getCharacterType()).isEqualTo(CharacterType.PRIMARY);
        assertThat(book.getCharacterPrefetchCompleted()).isTrue();
    }

    @Test
    void prefetchPrompt_requiresNamedPeopleAndFirstAppearanceBlurbs() {
        when(reasoningProvider.generate(any(), any())).thenReturn("[]");

        service.prefetchCharactersForBook(BOOK_ID);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(reasoningProvider).generate(prompt.capture(), any());
        String text = prompt.getValue();
        assertThat(text).contains(CharacterDiscoveryPromptRules.NAMED_PEOPLE_ONLY);
        assertThat(text).contains(CharacterDiscoveryPromptRules.REJECT_NON_PERSONS);
        assertThat(text).contains(CharacterDiscoveryPromptRules.NO_GLITCH_NAMES);
        assertThat(text).contains(CharacterDiscoveryPromptRules.FIRST_APPEARANCE_BLURB);
        assertThat(text).contains("tight PRIMARY");
        assertThat(text).doesNotContain("avoid major spoilers");
    }

    @Test
    void prefetch_dropsJunkNamesAndKeepsNamedPeople() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"bees","description":"insects in the garden","firstChapterNumber":1},
                  {"name":"The Moon","description":"hangs over the ship","firstChapterNumber":1},
                  {"name":"The Mule","description":"a pack animal","firstChapterNumber":1},
                  {"name":"Dorian","description":"a young man first seen in the studio","firstChapterNumber":1},
                  {"name":"Fortunato","description":"a wine connoisseur at carnival","firstChapterNumber":1},
                  {"name":"Elizabeth Bennet","description":"the second Bennet daughter","firstChapterNumber":1}
                ]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(eq(BOOK_ID), any()))
                .thenReturn(Optional.empty());
        when(characterRepository.save(any())).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("character-" + saved.getName());
            return saved;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        ArgumentCaptor<CharacterEntity> saved = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characterRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(CharacterEntity::getName)
                .containsExactlyInAnyOrder("Dorian", "Fortunato", "Elizabeth Bennet");
        assertThat(saved.getAllValues())
                .extracting(CharacterEntity::getCharacterType)
                .containsOnly(CharacterType.PRIMARY);
    }

    @Test
    void prefetch_typesKnowledgeNamesPrimaryIncludingArticleEpithets() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"Victor Frankenstein","description":"A Genevese student","firstChapterNumber":1},
                  {"name":"The Creature","description":"The being Victor animates","firstChapterNumber":5},
                  {"name":"The Monster","description":"How frightened villagers name him","firstChapterNumber":5},
                  {"name":"The Turk","description":"A prize-winning swordsman","firstChapterNumber":1},
                  {"characterName":"Dorian Gray","description":"A young man in Basil's studio","firstChapterNumber":1}
                ]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(eq(BOOK_ID), any()))
                .thenReturn(Optional.empty());
        when(characterRepository.save(any())).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("character-" + saved.getName());
            return saved;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        ArgumentCaptor<CharacterEntity> saved = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characterRepository, times(5)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(CharacterEntity::getName)
                .containsExactlyInAnyOrder(
                        "Victor Frankenstein", "The Creature", "The Monster", "The Turk", "Dorian Gray");
        assertThat(saved.getAllValues())
                .extracting(CharacterEntity::getCharacterType)
                .containsOnly(CharacterType.PRIMARY);
    }
}
