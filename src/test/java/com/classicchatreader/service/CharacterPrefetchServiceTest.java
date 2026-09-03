package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.ParagraphEntity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
        ReflectionTestUtils.setField(service, "maxPromptChars", 8000);
        ReflectionTestUtils.setField(service, "maxChapterTitleChars", 60);

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
        chapter.setTitle("Chapter I");
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(chapter));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID)).thenReturn(List.of(chapter));
    }

    @Test
    void prefetch_usesConfiguredReasoningProvider() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name": "Montresor", "description": "The narrator.", "firstChapterIndex": 0, "characterType": "PRIMARY"}]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Montresor"))
                .thenReturn(Optional.empty());
        when(characterRepository.save(any())).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("character-1");
            return saved;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(reasoningProvider, times(1)).generate(any(), any());
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
                [{"name": "Fortunato", "description": "The insulted connoisseur.", "firstChapterIndex": 0, "characterType": "PRIMARY"}]
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
        assertThat(text).contains(CharacterDiscoveryPromptRules.FIRST_CHAPTER_PLACEMENT);
        assertThat(text).contains("tight PRIMARY");
        assertThat(text).contains("firstChapterIndex");
        assertThat(text).contains("0-based");
        assertThat(text).contains("first present as a person");
        assertThat(text).doesNotContain("use 1 if you're unsure");
        assertThat(text).doesNotContain("avoid major spoilers");
        verify(reasoningProvider, times(1)).generate(any(), any());
    }

    @Test
    void buildPrefetchPrompt_includesCollectionInstructionAndInterpolatesTitleAuthor() {
        String text = service.buildPrefetchPrompt(
                "The Adventures of Sherlock Holmes", "Arthur Conan Doyle");

        assertThat(text).contains("The Adventures of Sherlock Holmes");
        assertThat(text).contains("Arthur Conan Doyle");
        assertThat(text).contains("short-story collection");
        assertThat(text).contains("linked tales");
        assertThat(text).contains("8-16");
        assertThat(text).contains("typically 3-8");
        assertThat(text).contains("tight PRIMARY");
        assertThat(text).contains("principal named character");
        assertThat(text).contains("throughout the book is not required");
        assertThat(text).contains("White Rabbit");
        assertThat(text).contains(CharacterDiscoveryPromptRules.NAMED_PEOPLE_ONLY);
        assertThat(text).contains(CharacterDiscoveryPromptRules.REJECT_NON_PERSONS);
        assertThat(text).contains(CharacterDiscoveryPromptRules.FIRST_APPEARANCE_BLURB);
        assertThat(text).contains(CharacterDiscoveryPromptRules.FIRST_CHAPTER_PLACEMENT);
        assertThat(text).contains(CharacterDiscoveryPromptRules.NO_GLITCH_NAMES);
        assertThat(text).contains("firstChapterIndex");
        assertThat(text).doesNotContain("use 1 if you're unsure");
        assertThat(text).doesNotContain("avoid major spoilers");
        assertThat(text).contains("first present as a person");
        assertThat(text).contains("exact full-name string");
        assertThat(text).contains("journal");
    }

    @Test
    void prefetch_dropsJunkNamesAndKeepsNamedPeople() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"bees","description":"insects in the garden","firstChapterIndex":0},
                  {"name":"The Moon","description":"hangs over the ship","firstChapterIndex":0},
                  {"name":"The Mule","description":"a pack animal","firstChapterIndex":0},
                  {"name":"Dorian","description":"a young man first seen in the studio","firstChapterIndex":0},
                  {"name":"Fortunato","description":"a wine connoisseur at carnival","firstChapterIndex":0},
                  {"name":"Elizabeth Bennet","description":"the second Bennet daughter","firstChapterIndex":0}
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
        ChapterEntity chapter5 = chapter("chapter-4", 4);
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 4)).thenReturn(Optional.of(chapter5));
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"Victor Frankenstein","description":"A Genevese student","firstChapterIndex":0},
                  {"name":"The Creature","description":"The being Victor animates","firstChapterIndex":4},
                  {"name":"The Monster","description":"How frightened villagers name him","firstChapterIndex":4},
                  {"name":"The Turk","description":"A prize-winning swordsman","firstChapterIndex":0},
                  {"characterName":"Dorian Gray","description":"A young man in Basil's studio","firstChapterIndex":0}
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
        assertThat(saved.getAllValues())
                .filteredOn(character -> "The Creature".equals(character.getName())
                        || "The Monster".equals(character.getName()))
                .extracting(character -> character.getFirstChapter().getChapterIndex())
                .containsOnly(4);
    }

    @Test
    void prefetch_prefersModelChapterWhenScanFindsLaterPhrase() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterIndex":0}]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Robinson Crusoe"))
                .thenReturn(Optional.empty());
        ArgumentCaptor<CharacterEntity> saved = ArgumentCaptor.forClass(CharacterEntity.class);
        when(characterRepository.save(any())).thenAnswer(invocation -> {
            CharacterEntity character = invocation.getArgument(0);
            character.setId("character-crusoe");
            return character;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(reasoningProvider, times(1)).generate(any(), any());
        verify(characterRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Robinson Crusoe");
        assertThat(saved.getValue().getFirstChapter().getId()).isEqualTo("chapter-0");
        assertThat(saved.getValue().getFirstChapter().getChapterIndex()).isZero();
        assertThat(saved.getValue().getFirstParagraphIndex()).isZero();
    }

    @Test
    void prefetch_promoteUsesModelChapterWhenScanFindsLaterPhrase() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);

        CharacterEntity existing = new CharacterEntity();
        existing.setId("character-crusoe");
        existing.setName("Robinson Crusoe");
        existing.setDescription("A castaway.");
        existing.setCharacterType(CharacterType.SECONDARY);
        existing.setFirstChapter(chapter8);
        existing.setFirstParagraphIndex(3);

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterIndex":0,"characterType":"PRIMARY"}]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Robinson Crusoe"))
                .thenReturn(Optional.of(existing));

        service.prefetchCharactersForBook(BOOK_ID);

        assertThat(existing.getCharacterType()).isEqualTo(CharacterType.PRIMARY);
        assertThat(existing.getFirstChapter().getId()).isEqualTo("chapter-0");
        assertThat(existing.getFirstParagraphIndex()).isZero();
    }

    @Test
    void prefetch_afterLatchClearMovesExistingPrimaryEarlierWhenModelMapsEarlierChapter() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);

        CharacterEntity existing = new CharacterEntity();
        existing.setId("character-crusoe");
        existing.setName("Robinson Crusoe");
        existing.setDescription("A castaway.");
        existing.setCharacterType(CharacterType.PRIMARY);
        existing.setFirstChapter(chapter8);
        existing.setFirstParagraphIndex(2);

        book.setCharacterPrefetchCompleted(true);
        book.setCharacterPrefetchCompleted(false);

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterIndex":0}]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Robinson Crusoe"))
                .thenReturn(Optional.of(existing));

        service.prefetchCharactersForBook(BOOK_ID);

        assertThat(existing.getCharacterType()).isEqualTo(CharacterType.PRIMARY);
        assertThat(existing.getFirstChapter().getId()).isEqualTo("chapter-0");
        assertThat(existing.getFirstChapter().getChapterIndex()).isZero();
        assertThat(existing.getFirstParagraphIndex()).isZero();
        verify(characterRepository).save(existing);
        assertThat(book.getCharacterPrefetchCompleted()).isTrue();
    }

    @Test
    void prefetch_doesNotMoveExistingPrimaryLaterWhenModelOrScanIsLater() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);

        CharacterEntity existing = new CharacterEntity();
        existing.setId("character-crusoe");
        existing.setName("Robinson Crusoe");
        existing.setCharacterType(CharacterType.PRIMARY);
        existing.setFirstChapter(chapter1);
        existing.setFirstParagraphIndex(0);

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterIndex":7}]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Robinson Crusoe"))
                .thenReturn(Optional.of(existing));

        service.prefetchCharactersForBook(BOOK_ID);

        assertThat(existing.getFirstChapter().getId()).isEqualTo("chapter-0");
        assertThat(existing.getFirstParagraphIndex()).isZero();
        verify(characterRepository, never()).save(existing);
    }

    @Test
    void prefetch_doesNotMoveExistingPrimaryWithLaterScanWhenModelOmitsChapter() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);

        CharacterEntity existing = new CharacterEntity();
        existing.setId("character-crusoe");
        existing.setName("Robinson Crusoe");
        existing.setCharacterType(CharacterType.PRIMARY);
        existing.setFirstChapter(chapter1);
        existing.setFirstParagraphIndex(0);

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea."}]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Robinson Crusoe"))
                .thenReturn(Optional.of(existing));

        service.prefetchCharactersForBook(BOOK_ID);

        assertThat(existing.getFirstChapter().getId()).isEqualTo("chapter-0");
        verify(characterRepository, never()).save(existing);
    }

    @Test
    void prefetch_usesScanWhenModelOmitsChapter() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea."}]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Robinson Crusoe"))
                .thenReturn(Optional.empty());
        ArgumentCaptor<CharacterEntity> saved = ArgumentCaptor.forClass(CharacterEntity.class);
        when(characterRepository.save(any())).thenAnswer(invocation -> {
            CharacterEntity character = invocation.getArgument(0);
            character.setId("character-crusoe");
            return character;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(characterRepository).save(saved.capture());
        assertThat(saved.getValue().getFirstChapter().getId()).isEqualTo("chapter-7");
        assertThat(saved.getValue().getFirstParagraphIndex()).isEqualTo(2);
    }

    @Test
    void prefetch_invalidIndexDoesNotCoerceToPreface() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        chapter1.setTitle("CHAPTER I");
        when(chapterRepository.findByBookIdAndChapterIndex(eq(BOOK_ID), anyInt())).thenReturn(Optional.empty());
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID)).thenReturn(List.of(preface, chapter1));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex(any())).thenReturn(List.of());

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Polly","description":"A country girl.","firstChapterIndex":99}]
                """);

        service.prefetchCharactersForBook(BOOK_ID);

        verify(characterRepository, never()).save(any(CharacterEntity.class));
        assertThat(book.getCharacterPrefetchCompleted()).isTrue();
    }

    @Test
    void prefetch_legacyFirstChapterNumberDoesNotMapStoryOneOntoPreface() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        chapter1.setTitle("CHAPTER I");
        when(chapterRepository.findByBookIdAndChapterIndex(eq(BOOK_ID), anyInt())).thenReturn(Optional.empty());
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID)).thenReturn(List.of(preface, chapter1));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex(any())).thenReturn(List.of());

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Polly","description":"A country girl.","firstChapterNumber":1}]
                """);

        service.prefetchCharactersForBook(BOOK_ID);

        verify(characterRepository, never()).save(any(CharacterEntity.class));
    }

    @Test
    void prefetch_ofgModelIndexOneMapsChapterINotPreface() {
        stubOldFashionedGirlChapters();
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"Polly","description":"A country girl.","firstChapterIndex":1,"characterType":"PRIMARY"},
                  {"name":"Fanny","description":"A city girl.","firstChapterIndex":1,"characterType":"PRIMARY"},
                  {"name":"Tom","description":"Fanny's brother.","firstChapterIndex":2,"characterType":"PRIMARY"},
                  {"name":"Maud","description":"The youngest Shaw.","firstChapterIndex":3,"characterType":"SECONDARY"},
                  {"name":"Mrs. Shaw","description":"Fanny's mother.","firstChapterIndex":1,"characterType":"SECONDARY"},
                  {"name":"Grandma","description":"Polly's grandmother.","firstChapterIndex":5,"characterType":"SECONDARY"}
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

        verify(reasoningProvider, times(1)).generate(any(), any());
        ArgumentCaptor<CharacterEntity> saved = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characterRepository, times(6)).save(saved.capture());
        assertThat(saved.getAllValues())
                .filteredOn(character -> "Polly".equals(character.getName()))
                .extracting(character -> character.getFirstChapter().getChapterIndex())
                .containsExactly(1);
        assertThat(saved.getAllValues())
                .filteredOn(character -> "Polly".equals(character.getName()))
                .extracting(character -> character.getFirstChapter().getTitle())
                .containsExactly("CHAPTER I");
        assertThat(saved.getAllValues())
                .extracting(character -> character.getFirstChapter().getChapterIndex())
                .containsExactlyInAnyOrder(1, 1, 2, 3, 1, 5);
        assertThat(saved.getAllValues())
                .filteredOn(character -> "Maud".equals(character.getName()))
                .extracting(CharacterEntity::getCharacterType)
                .containsExactly(CharacterType.SECONDARY);
        assertThat(saved.getAllValues())
                .filteredOn(character -> "Polly".equals(character.getName()))
                .extracting(CharacterEntity::getCharacterType)
                .containsExactly(CharacterType.PRIMARY);
    }

    @Test
    void prefetch_prefaceIndexZeroIsValidWhenModelChoosesIt() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        chapter1.setTitle("CHAPTER I");
        when(chapterRepository.findByBookIdAndChapterIndex(eq(BOOK_ID), anyInt())).thenReturn(Optional.empty());
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID)).thenReturn(List.of(preface, chapter1));

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Editor","description":"The preface narrator.","firstChapterIndex":0,"characterType":"SECONDARY"}]
                """);
        when(characterRepository.findByBookIdAndNameIgnoreCase(BOOK_ID, "Editor"))
                .thenReturn(Optional.empty());
        when(characterRepository.save(any())).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("character-editor");
            return saved;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        ArgumentCaptor<CharacterEntity> saved = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characterRepository).save(saved.capture());
        assertThat(saved.getValue().getFirstChapter().getChapterIndex()).isZero();
        assertThat(saved.getValue().getFirstChapter().getTitle()).isEqualTo("Preface");
        assertThat(saved.getValue().getCharacterType()).isEqualTo(CharacterType.SECONDARY);
    }

    @Test
    void prefetch_omittedAndMalformedCharacterTypeDefaultToPrimary() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"Montresor","description":"Narrator.","firstChapterIndex":0},
                  {"name":"Fortunato","description":"Victim.","firstChapterIndex":0,"characterType":"sidekick"}
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
        verify(characterRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(CharacterEntity::getCharacterType)
                .containsOnly(CharacterType.PRIMARY);
    }

    @Test
    void buildPrefetchPrompt_includesChapterMapAndStaysBoundedFor200LongTitles() {
        List<ChapterEntity> chapters = new ArrayList<>();
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Author's Preface to the Excessively Long Second Edition " + "X".repeat(80));
        chapters.add(preface);
        for (int i = 1; i < 200; i++) {
            ChapterEntity chapterEntity = chapter("chapter-" + i, i);
            chapterEntity.setTitle("CHAPTER " + i + " " + "Y".repeat(120));
            chapters.add(chapterEntity);
        }

        String prompt = service.buildPrefetchPrompt("An Old-Fashioned Girl", "Louisa May Alcott", chapters);

        assertThat(prompt.length()).isLessThanOrEqualTo(8000);
        assertThat(prompt).contains("0. ");
        assertThat(prompt).contains("Author's Preface");
        assertThat(prompt).contains("front matter");
        assertThat(prompt).contains("firstChapterIndex");
        assertThat(prompt).contains("not first presence as a person");
        assertThat(prompt).doesNotContain("use 1 if you're unsure");
        assertThat(prompt).doesNotContain("Y".repeat(120));
    }

    @Test
    void refresh_doesNotOverwriteModelPlacedChapterWithLaterScan() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);

        CharacterEntity existing = new CharacterEntity();
        existing.setId("character-crusoe");
        existing.setName("Robinson Crusoe");
        existing.setCharacterType(CharacterType.PRIMARY);
        existing.setFirstChapter(chapter1);
        existing.setFirstParagraphIndex(0);
        when(characterRepository.findByBookIdOrderByCreatedAt(BOOK_ID)).thenReturn(List.of(existing));

        int updated = service.refreshPrimaryCharacterPositionsForBook(BOOK_ID);

        assertThat(updated).isZero();
        assertThat(existing.getFirstChapter().getId()).isEqualTo("chapter-0");
        verify(characterRepository, never()).save(any(CharacterEntity.class));
    }

    @Test
    void refresh_usesScanOnlyWhenFirstChapterIsMissing() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);

        CharacterEntity existing = new CharacterEntity();
        existing.setId("character-crusoe");
        existing.setName("Robinson Crusoe");
        existing.setCharacterType(CharacterType.PRIMARY);
        existing.setFirstChapter(null);
        when(characterRepository.findByBookIdOrderByCreatedAt(BOOK_ID)).thenReturn(List.of(existing));

        int updated = service.refreshPrimaryCharacterPositionsForBook(BOOK_ID);

        assertThat(updated).isEqualTo(1);
        assertThat(existing.getFirstChapter().getId()).isEqualTo("chapter-7");
        assertThat(existing.getFirstParagraphIndex()).isEqualTo(2);
        verify(characterRepository).save(existing);
    }

    private static ChapterEntity chapter(String id, int chapterIndex) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(id);
        chapter.setChapterIndex(chapterIndex);
        chapter.setTitle("Chapter " + (chapterIndex + 1));
        return chapter;
    }

    private void stubCrusoeChapters(ChapterEntity chapter1, ChapterEntity chapter8) {
        when(chapterRepository.findByBookIdAndChapterIndex(eq(BOOK_ID), anyInt())).thenReturn(Optional.empty());
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 7)).thenReturn(Optional.of(chapter8));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID)).thenReturn(List.of(chapter1, chapter8));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-0")).thenReturn(List.of(
                paragraph(0, "My father was a foreigner of Bremen who settled at York. "
                        + "I was called Robinson Kreutznaer, but by the usual corruption of words "
                        + "in England we are now called Crusoe.")));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-7")).thenReturn(List.of(
                paragraph(2, "I, poor miserable Robinson Crusoe, being shipwrecked during a dreadful storm, came on shore.")));
    }

    private void stubOldFashionedGirlChapters() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        chapter1.setTitle("CHAPTER I");
        ChapterEntity chapter2 = chapter("chapter-2", 2);
        chapter2.setTitle("CHAPTER II");
        ChapterEntity chapter3 = chapter("chapter-3", 3);
        chapter3.setTitle("CHAPTER III");
        ChapterEntity chapter4 = chapter("chapter-4", 4);
        chapter4.setTitle("CHAPTER IV");
        ChapterEntity chapter5 = chapter("chapter-5", 5);
        chapter5.setTitle("CHAPTER V");

        when(chapterRepository.findByBookIdAndChapterIndex(eq(BOOK_ID), anyInt())).thenReturn(Optional.empty());
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 2)).thenReturn(Optional.of(chapter2));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 3)).thenReturn(Optional.of(chapter3));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 4)).thenReturn(Optional.of(chapter4));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 5)).thenReturn(Optional.of(chapter5));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID)).thenReturn(List.of(
                preface, chapter1, chapter2, chapter3, chapter4, chapter5));
    }

    private static ParagraphEntity paragraph(int index, String content) {
        ParagraphEntity paragraph = new ParagraphEntity();
        paragraph.setParagraphIndex(index);
        paragraph.setContent(content);
        return paragraph;
    }
}
