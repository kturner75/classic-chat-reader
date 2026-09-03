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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
        ReflectionTestUtils.setField(service, "placementRetryMaxContextChars", 12000);
        ReflectionTestUtils.setField(service, "placementRetryParagraphsPerChapter", 3);

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
        assertThat(text).contains(CharacterDiscoveryPromptRules.FIRST_CHAPTER_PLACEMENT);
        assertThat(text).contains("tight PRIMARY");
        assertThat(text).doesNotContain("avoid major spoilers");
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
        assertThat(text).doesNotContain("avoid major spoilers");
        assertThat(text).contains("1-based story chapter");
        assertThat(text).contains("first present as a person");
        assertThat(text).contains("exact full-name string");
        assertThat(text).contains("journal");
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
        ChapterEntity chapter5 = chapter("chapter-4", 4);
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 4)).thenReturn(Optional.of(chapter5));
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
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterNumber":1}]
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
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterNumber":1}]
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

        // Already prefetched and pinned at the journal chapter; latch-clear
        // must let prefetch re-ask the model and move him. Do not require delete.
        book.setCharacterPrefetchCompleted(true);
        book.setCharacterPrefetchCompleted(false);

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterNumber":1}]
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
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterNumber":8}]
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
    void prefetch_usesScanWhenModelChapterDoesNotMap() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        ChapterEntity chapter8 = chapter("chapter-7", 7);
        stubCrusoeChapters(chapter1, chapter8);
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [{"name":"Robinson Crusoe","description":"A York youth who goes to sea.","firstChapterNumber":99}]
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

    private static ParagraphEntity paragraph(int index, String content) {
        ParagraphEntity paragraph = new ParagraphEntity();
        paragraph.setParagraphIndex(index);
        paragraph.setContent(content);
        return paragraph;
    }

    @Test
    void detectSuspiciousBatchCollapse_trueWhenMultipleCharactersSharePreface() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));

        List<CharacterPrefetchService.PrefetchedCharacter> cast = List.of(
                new CharacterPrefetchService.PrefetchedCharacter("Polly", "A country girl.", 1),
                new CharacterPrefetchService.PrefetchedCharacter("Fanny", "A city girl.", 1),
                new CharacterPrefetchService.PrefetchedCharacter("Tom", "Fanny's brother.", 1),
                new CharacterPrefetchService.PrefetchedCharacter("Maud", "The youngest Shaw.", 1),
                new CharacterPrefetchService.PrefetchedCharacter("Mrs. Shaw", "Fanny's mother.", 1),
                new CharacterPrefetchService.PrefetchedCharacter("Mr. Shaw", "Fanny's father.", 1));

        assertThat(service.detectSuspiciousBatchCollapse(BOOK_ID, cast)).isTrue();
    }

    @Test
    void detectSuspiciousBatchCollapse_falseForSingleCharacterAtPreface() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));

        List<CharacterPrefetchService.PrefetchedCharacter> cast = List.of(
                new CharacterPrefetchService.PrefetchedCharacter("Narrator", "The preface voice.", 1));

        assertThat(service.detectSuspiciousBatchCollapse(BOOK_ID, cast)).isFalse();
    }

    @Test
    void detectSuspiciousBatchCollapse_falseForDispersedValidPlacements() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        chapter1.setTitle("Chapter I");
        ChapterEntity chapter2 = chapter("chapter-2", 2);
        chapter2.setTitle("Chapter II");
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 2)).thenReturn(Optional.of(chapter2));

        List<CharacterPrefetchService.PrefetchedCharacter> cast = List.of(
                new CharacterPrefetchService.PrefetchedCharacter("Mary", "Appears early.", 2),
                new CharacterPrefetchService.PrefetchedCharacter("Colin", "Appears later.", 3));

        assertThat(service.detectSuspiciousBatchCollapse(BOOK_ID, cast)).isFalse();
    }

    @Test
    void detectSuspiciousBatchCollapse_falseWhenOneCharacterMapsElsewhere() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        chapter1.setTitle("Chapter I");
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter1));

        List<CharacterPrefetchService.PrefetchedCharacter> cast = List.of(
                new CharacterPrefetchService.PrefetchedCharacter("Polly", "Country girl.", 1),
                new CharacterPrefetchService.PrefetchedCharacter("Fanny", "City girl.", 2));

        assertThat(service.detectSuspiciousBatchCollapse(BOOK_ID, cast)).isFalse();
    }

    @Test
    void prefetch_collapsedOFGBatchTriggersPlacementRetryAndDispersedChapters() {
        stubOldFashionedGirlChapters();
        AtomicInteger llmCalls = new AtomicInteger();
        when(reasoningProvider.generate(any(), any())).thenAnswer(invocation -> {
            if (llmCalls.getAndIncrement() == 0) {
                return """
                        [
                          {"name":"Polly","description":"A country girl.","firstChapterNumber":1},
                          {"name":"Fanny","description":"A city girl.","firstChapterNumber":1},
                          {"name":"Tom","description":"Fanny's brother.","firstChapterNumber":1},
                          {"name":"Maud","description":"The youngest Shaw.","firstChapterNumber":1},
                          {"name":"Mrs. Shaw","description":"Fanny's mother.","firstChapterNumber":1},
                          {"name":"Mr. Shaw","description":"Fanny's father.","firstChapterNumber":1}
                        ]
                        """;
            }
            return """
                    [
                      {"name":"Polly","firstChapterNumber":2},
                      {"name":"Fanny","firstChapterNumber":2},
                      {"name":"Tom","firstChapterNumber":3},
                      {"name":"Maud","firstChapterNumber":4},
                      {"name":"Mrs. Shaw","firstChapterNumber":2},
                      {"name":"Mr. Shaw","firstChapterNumber":5}
                    ]
                    """;
        });
        when(characterRepository.findByBookIdAndNameIgnoreCase(eq(BOOK_ID), any()))
                .thenReturn(Optional.empty());
        when(characterRepository.save(any())).thenAnswer(invocation -> {
            CharacterEntity saved = invocation.getArgument(0);
            saved.setId("character-" + saved.getName());
            return saved;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(reasoningProvider, times(2)).generate(any(), any());
        ArgumentCaptor<CharacterEntity> saved = ArgumentCaptor.forClass(CharacterEntity.class);
        verify(characterRepository, times(6)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(character -> character.getName())
                .containsExactlyInAnyOrder("Polly", "Fanny", "Tom", "Maud", "Mrs. Shaw", "Mr. Shaw");
        assertThat(saved.getAllValues())
                .extracting(character -> character.getFirstChapter().getChapterIndex())
                .doesNotContain(0);
        assertThat(saved.getAllValues())
                .extracting(character -> character.getFirstChapter().getChapterIndex())
                .contains(1, 2, 3, 4);
        assertThat(saved.getAllValues())
                .extracting(CharacterEntity::getCharacterType)
                .containsOnly(CharacterType.PRIMARY);
    }

    @Test
    void prefetch_validDispersedBatchUsesSingleLlmCall() {
        ChapterEntity chapter1 = chapter("chapter-0", 0);
        chapter1.setTitle("Chapter I");
        ChapterEntity chapter2 = chapter("chapter-1", 1);
        chapter2.setTitle("Chapter II");
        ChapterEntity chapter3 = chapter("chapter-2", 2);
        chapter3.setTitle("Chapter III");
        when(chapterRepository.findByBookIdAndChapterIndex(eq(BOOK_ID), anyInt())).thenReturn(Optional.empty());
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter2));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 2)).thenReturn(Optional.of(chapter3));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID))
                .thenReturn(List.of(chapter1, chapter2, chapter3));

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"Mary Lennox","description":"A sour little girl.","firstChapterNumber":1},
                  {"name":"Colin Craven","description":"A bedridden boy.","firstChapterNumber":3},
                  {"name":"Dickon","description":"A boy who talks to animals.","firstChapterNumber":2}
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
        verify(characterRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(character -> character.getFirstChapter().getChapterIndex())
                .containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void prefetch_collapsedBatchDoesNotUsePhraseScanWhenRetryFails() {
        stubOldFashionedGirlChapters();
        when(reasoningProvider.generate(any(), any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("placing first appearances")) {
                return """
                        [
                          {"name":"Polly","firstChapterNumber":1},
                          {"name":"Fanny","firstChapterNumber":1},
                          {"name":"Tom","firstChapterNumber":1}
                        ]
                        """;
            }
            return """
                    [
                      {"name":"Polly","description":"A country girl.","firstChapterNumber":1},
                      {"name":"Fanny","description":"A city girl.","firstChapterNumber":1},
                      {"name":"Tom","description":"Fanny's brother.","firstChapterNumber":1}
                    ]
                    """;
        });

        service.prefetchCharactersForBook(BOOK_ID);

        verify(reasoningProvider, times(2)).generate(any(), any());
        verify(characterRepository, never()).save(any(CharacterEntity.class));
        assertThat(book.getCharacterPrefetchCompleted()).isTrue();
    }

    @Test
    void prefetch_legitimatePrefaceCharacterAmongDispersedBatchIsUnchanged() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        chapter1.setTitle("Chapter I");
        ChapterEntity chapter2 = chapter("chapter-2", 2);
        chapter2.setTitle("Chapter II");
        when(chapterRepository.findByBookIdAndChapterIndex(eq(BOOK_ID), anyInt())).thenReturn(Optional.empty());
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 2)).thenReturn(Optional.of(chapter2));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID))
                .thenReturn(List.of(preface, chapter1, chapter2));

        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"Editor","description":"The preface narrator.","firstChapterNumber":1},
                  {"name":"Hero","description":"The story lead.","firstChapterNumber":2},
                  {"name":"Sidekick","description":"The companion.","firstChapterNumber":3}
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
        verify(characterRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .filteredOn(character -> "Editor".equals(character.getName()))
                .extracting(character -> character.getFirstChapter().getChapterIndex())
                .containsExactly(0);
    }

    @Test
    void buildPlacementRetryPrompt_includesRosterChapterMapAndBoundedExcerpts() {
        stubOldFashionedGirlChapters();
        List<CharacterPrefetchService.PrefetchedCharacter> cast = List.of(
                new CharacterPrefetchService.PrefetchedCharacter("Polly", "A country girl.", 1),
                new CharacterPrefetchService.PrefetchedCharacter("Fanny", "A city girl.", 1));

        String prompt = service.buildPlacementRetryPrompt(book, cast);

        assertThat(prompt).contains("Polly");
        assertThat(prompt).contains("Fanny");
        assertThat(prompt).contains("CHAPTER MAP");
        assertThat(prompt).contains("Preface");
        assertThat(prompt).contains("front matter");
        assertThat(prompt).contains("CHAPTER EXCERPTS");
        assertThat(prompt).contains("Do NOT rediscover");
        assertThat(prompt).contains("firstChapterNumber");
        assertThat(prompt).doesNotContain("phrase-scan");
    }

    private void stubOldFashionedGirlChapters() {
        ChapterEntity preface = chapter("preface", 0);
        preface.setTitle("Preface");
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        chapter1.setTitle("Chapter I. Polly Arrives");
        ChapterEntity chapter2 = chapter("chapter-2", 2);
        chapter2.setTitle("Chapter II. New Fashions");
        ChapterEntity chapter3 = chapter("chapter-3", 3);
        chapter3.setTitle("Chapter III. Tom's Prank");
        ChapterEntity chapter4 = chapter("chapter-4", 4);
        chapter4.setTitle("Chapter IV. Maud's Party");
        ChapterEntity chapter5 = chapter("chapter-5", 5);
        chapter5.setTitle("Chapter V. Mr. Shaw Returns");

        when(chapterRepository.findByBookIdAndChapterIndex(eq(BOOK_ID), anyInt())).thenReturn(Optional.empty());
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 0)).thenReturn(Optional.of(preface));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 1)).thenReturn(Optional.of(chapter1));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 2)).thenReturn(Optional.of(chapter2));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 3)).thenReturn(Optional.of(chapter3));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 4)).thenReturn(Optional.of(chapter4));
        when(chapterRepository.findByBookIdAndChapterIndex(BOOK_ID, 5)).thenReturn(Optional.of(chapter5));
        when(chapterRepository.findByBookIdOrderByChapterIndex(BOOK_ID)).thenReturn(List.of(
                preface, chapter1, chapter2, chapter3, chapter4, chapter5));

        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("preface")).thenReturn(List.of(
                paragraph(0, "This story follows Polly, Fanny, Tom, and the Shaw family.")));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1")).thenReturn(List.of(
                paragraph(0, "Polly Milton arrived at the Shaw house on a snowy afternoon.")));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-2")).thenReturn(List.of(
                paragraph(0, "Fanny showed Polly the city fashions that very morning.")));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-3")).thenReturn(List.of(
                paragraph(0, "Tom Shaw laughed and planned another prank in the hall.")));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-4")).thenReturn(List.of(
                paragraph(0, "Little Maud Shaw chattered about her birthday party.")));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-5")).thenReturn(List.of(
                paragraph(0, "Mr. Shaw came home and greeted his wife in the parlor.")));
    }
}
