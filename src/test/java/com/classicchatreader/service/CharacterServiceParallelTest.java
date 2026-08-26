package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterAnalysisEntity;
import com.classicchatreader.entity.ChapterAnalysisStatus;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CharacterServiceParallelTest {

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
        ReflectionTestUtils.setField(service, "cacheOnly", false);
        ReflectionTestUtils.setField(service, "imagineMaxInFlight", 2);
        ReflectionTestUtils.setField(service, "analysisLeaseMinutes", 15);
        ReflectionTestUtils.setField(service, "portraitLeaseMinutes", 20);
        ReflectionTestUtils.setField(service, "configuredWorkerId", "character-test");
        ReflectionTestUtils.setField(service, "maxRetryAttempts", 3);
        ReflectionTestUtils.setField(service, "initialRetryDelaySeconds", 30);
        ReflectionTestUtils.setField(service, "maxRetryDelaySeconds", 600);

        book = new BookEntity();
        book.setId("book-1");
        book.setTitle("Pride and Prejudice");
        book.setAuthor("Jane Austen");
        book.setSource("gutenberg");
        book.setSourceId("1342");

        chapter = new ChapterEntity(1, "Chapter I");
        chapter.setId("chapter-1");
        chapter.setBook(book);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void initStartsMoreThanOneImagineWorker() {
        service.init();
        assertThat(service.getImagineWorkerCount()).isEqualTo(2);
        assertThat(service.isQueueProcessorRunning()).isTrue();
    }

    @Test
    void twoPortraitWorkersGenerateDistinctCharactersAtOnce() throws Exception {
        CharacterEntity elizabeth = character("character-1", "Elizabeth Bennet");
        CharacterEntity darcy = character("character-2", "Fitzwilliam Darcy");
        stubPortraitGeneration(elizabeth);
        stubPortraitGeneration(darcy);

        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(portraitImageGenerator.generatePortrait(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(inFlight.get(), Math::max);
                    bothStarted.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release timed out");
                    }
                    inFlight.decrementAndGet();
                    return invocation.getArgument(2);
                });

        service.init();
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of(elizabeth, darcy));
        assertThat(service.forceQueuePendingPortraitsForBook("book-1")).isEqualTo(2);

        assertThat(bothStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(maxInFlight.get()).isEqualTo(2);
        release.countDown();
        verify(portraitImageGenerator, timeout(2000).times(2))
                .generatePortrait(anyString(), anyString(), anyString());
    }

    @Test
    void leaseKeepsASecondWorkerOffTheSameCharacter() throws Exception {
        CharacterEntity elizabeth = character("character-1", "Elizabeth Bennet");
        stubPortraitGeneration(elizabeth);
        when(characterRepository.claimPortraitLease(
                eq("character-1"), any(), any(), any(),
                eq(CharacterStatus.PENDING), eq(CharacterStatus.GENERATING)))
                .thenReturn(1)
                .thenReturn(0);
        when(portraitImageGenerator.generatePortrait(anyString(), anyString(), anyString()))
                .thenReturn("books/gutenberg/1342/portraits/characters/elizabeth-bennet.png");

        service.init();
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of(elizabeth, elizabeth));
        service.forceQueuePendingPortraitsForBook("book-1");

        verify(portraitImageGenerator, timeout(2000).times(1))
                .generatePortrait(anyString(), anyString(), anyString());
        verify(characterRepository, times(2)).claimPortraitLease(
                eq("character-1"), any(), any(), any(),
                eq(CharacterStatus.PENDING), eq(CharacterStatus.GENERATING));
    }

    @Test
    void analysisRunsOnItsOwnPathWhileAPortraitIsInFlight() throws Exception {
        CharacterEntity elizabeth = character("character-1", "Elizabeth Bennet");
        stubPortraitGeneration(elizabeth);
        CountDownLatch portraitStarted = new CountDownLatch(1);
        CountDownLatch releasePortrait = new CountDownLatch(1);
        when(portraitImageGenerator.generatePortrait(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    portraitStarted.countDown();
                    if (!releasePortrait.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("portrait release timed out");
                    }
                    return invocation.getArgument(2);
                });

        ChapterAnalysisEntity analysis = new ChapterAnalysisEntity(chapter);
        when(chapterAnalysisRepository.claimAnalysisLease(
                eq("chapter-1"), any(), any(), any(),
                eq(ChapterAnalysisStatus.PENDING), eq(ChapterAnalysisStatus.GENERATING)))
                .thenReturn(1);
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt("book-1")).thenReturn(List.of());
        when(chapterAnalysisRepository.findByChapterId("chapter-1")).thenReturn(Optional.of(analysis));
        when(chapterAnalysisRepository.findByChapterBookIdAndStatus("book-1", ChapterAnalysisStatus.PENDING))
                .thenReturn(List.of(analysis));
        when(chapterAnalysisRepository.findByChapterBookIdAndStatusIsNull("book-1")).thenReturn(List.of());

        ReflectionTestUtils.setField(service, "imagineMaxInFlight", 1);
        service.init();
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of(elizabeth));
        service.forceQueuePendingPortraitsForBook("book-1");
        assertThat(portraitStarted.await(3, TimeUnit.SECONDS)).isTrue();

        service.forceQueuePendingAnalysesForBook("book-1");
        verify(chapterAnalysisRepository, timeout(2000)).save(analysis);
        assertThat(analysis.getStatus()).isEqualTo(ChapterAnalysisStatus.COMPLETED);
        verify(portraitImageGenerator, times(1)).generatePortrait(anyString(), anyString(), anyString());

        releasePortrait.countDown();
    }

    @Test
    void cacheOnlyWorkersDoNotGeneratePortraits() throws Exception {
        ReflectionTestUtils.setField(service, "cacheOnly", true);
        CharacterEntity elizabeth = character("character-1", "Elizabeth Bennet");
        service.init();
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of(elizabeth));
        assertThat(service.forceQueuePendingPortraitsForBook("book-1")).isEqualTo(0);
        Thread.sleep(200);
        verify(portraitImageGenerator, never()).generatePortrait(any(), any(), any());
        verify(characterRepository, never()).claimPortraitLease(any(), any(), any(), any(), any(), any());
    }

    private void stubPortraitGeneration(CharacterEntity character) {
        String characterId = character.getId();
        when(characterRepository.claimPortraitLease(
                eq(characterId), any(), any(), any(),
                eq(CharacterStatus.PENDING), eq(CharacterStatus.GENERATING)))
                .thenReturn(1);
        when(characterRepository.findByIdWithBookAndChapter(characterId)).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(anyString())).thenReturn(false);
        when(illustrationService.getOrAnalyzeBookStyle("book-1", false))
                .thenReturn(IllustrationSettings.defaults());
        when(portraitService.generatePortraitPrompt(any(), any(), any(), any(), any()))
                .thenReturn("a portrait");
        when(characterRepository.findById(characterId)).thenReturn(Optional.of(character));
    }

    private CharacterEntity character(String id, String name) {
        CharacterEntity character = new CharacterEntity(book, name, "A character", chapter, 0);
        character.setId(id);
        character.setStatus(CharacterStatus.PENDING);
        return character;
    }
}
