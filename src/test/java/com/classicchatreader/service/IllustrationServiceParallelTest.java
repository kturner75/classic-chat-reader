package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.IllustrationEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.IllustrationRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IllustrationServiceParallelTest {

    @Mock private IllustrationRepository illustrationRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private BookRepository bookRepository;
    @Mock private ParagraphRepository paragraphRepository;
    @Mock private IllustrationPromptService promptService;
    @Mock private IllustrationStyleAnalysisService styleAnalysisService;
    @Mock private ComfyUIService comfyUIService;
    @Mock private IllustrationImageGeneratorService illustrationImageGenerator;
    @Mock private IllustrationPortraitReferences portraitReferences;
    @Mock private CdnAssetService cdnAssetService;

    private IllustrationService service;

    @BeforeEach
    void setUp() {
        service = new IllustrationService(
                illustrationRepository,
                chapterRepository,
                bookRepository,
                paragraphRepository,
                promptService,
                styleAnalysisService,
                comfyUIService,
                illustrationImageGenerator,
                portraitReferences,
                new AssetKeyService(),
                cdnAssetService
        );
        service.setSelf(service);
        ReflectionTestUtils.setField(service, "cacheOnly", false);
        ReflectionTestUtils.setField(service, "imagineMaxInFlight", 2);
        ReflectionTestUtils.setField(service, "illustrationLeaseMinutes", 20);
        ReflectionTestUtils.setField(service, "configuredWorkerId", "illustration-test");
        ReflectionTestUtils.setField(service, "maxRetryAttempts", 3);
        ReflectionTestUtils.setField(service, "initialRetryDelaySeconds", 30);
        ReflectionTestUtils.setField(service, "maxRetryDelaySeconds", 600);
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
    void twoWorkersGenerateDistinctChaptersAtOnce() throws Exception {
        ChapterEntity chapter1 = chapter("chapter-1", 1);
        ChapterEntity chapter2 = chapter("chapter-2", 2);
        stubChapterGeneration(chapter1);
        stubChapterGeneration(chapter2);

        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(illustrationImageGenerator.generateIllustration(anyString(), anyString(), anyString(), any()))
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
        when(illustrationRepository.findByChapterBookIdAndStatus("book-1", IllustrationStatus.PENDING))
                .thenReturn(List.of(new IllustrationEntity(chapter1), new IllustrationEntity(chapter2)));
        assertThat(service.forceQueuePendingForBook("book-1")).isEqualTo(2);

        assertThat(bothStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(maxInFlight.get()).isEqualTo(2);
        release.countDown();
        verify(illustrationImageGenerator, timeout(2000).times(2))
                .generateIllustration(anyString(), anyString(), anyString(), any());
    }

    @Test
    void leaseKeepsASecondWorkerOffTheSameChapter() throws Exception {
        ChapterEntity chapter = chapter("chapter-1", 1);
        stubChapterGeneration(chapter);
        when(illustrationRepository.claimGenerationLease(
                eq("chapter-1"), any(), any(), any(),
                eq(IllustrationStatus.PENDING), eq(IllustrationStatus.GENERATING)))
                .thenReturn(1)
                .thenReturn(0);
        when(illustrationImageGenerator.generateIllustration(anyString(), anyString(), anyString(), any()))
                .thenReturn("books/gutenberg/1342/illustrations/chapters/1.png");

        service.init();
        IllustrationEntity pending = new IllustrationEntity(chapter);
        when(illustrationRepository.findByChapterBookIdAndStatus("book-1", IllustrationStatus.PENDING))
                .thenReturn(List.of(pending, pending));
        service.forceQueuePendingForBook("book-1");

        verify(illustrationImageGenerator, timeout(2000).times(1))
                .generateIllustration(anyString(), anyString(), anyString(), any());
        verify(illustrationRepository, times(2)).claimGenerationLease(
                eq("chapter-1"), any(), any(), any(),
                eq(IllustrationStatus.PENDING), eq(IllustrationStatus.GENERATING));
    }

    @Test
    void interruptAfterClaimResetsLeaseWithoutRetry() throws Exception {
        ChapterEntity chapter = chapter("chapter-1", 1);
        stubChapterGeneration(chapter);
        IllustrationEntity illustration = new IllustrationEntity(chapter);
        illustration.setStatus(IllustrationStatus.GENERATING);
        illustration.setLeaseOwner("illustration-test");
        illustration.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(20));
        illustration.setRetryCount(0);
        when(illustrationRepository.findByChapterId("chapter-1")).thenReturn(Optional.of(illustration));
        CountDownLatch started = new CountDownLatch(1);
        when(illustrationImageGenerator.generateIllustration(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    throw new InterruptedException("imagine wait interrupted");
                });

        service.init();
        when(illustrationRepository.findByChapterBookIdAndStatus("book-1", IllustrationStatus.PENDING))
                .thenReturn(List.of(new IllustrationEntity(chapter)));
        service.forceQueuePendingForBook("book-1");

        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        // Prompt persist also save()s this row before generateIllustration throws.
        // Wait for the interrupt handler's PENDING reset, not that earlier write.
        verify(illustrationRepository, timeout(2000).atLeastOnce()).save(argThat(saved ->
                saved == illustration
                        && saved.getStatus() == IllustrationStatus.PENDING
                        && saved.getLeaseOwner() == null
                        && saved.getLeaseExpiresAt() == null));
        assertThat(illustration.getRetryCount()).isEqualTo(0);
        assertThat(illustration.getNextRetryAt()).isNull();
    }

    @Test
    void styleLockIsOutsideRequiresNewTransaction() throws Exception {
        Transactional outer = IllustrationService.class
                .getMethod("getOrAnalyzeBookStyle", String.class, boolean.class)
                .getAnnotation(Transactional.class);
        Transactional inner = IllustrationService.class
                .getMethod("analyzeBookStyleInNewTransaction", String.class, boolean.class)
                .getAnnotation(Transactional.class);

        assertThat(outer)
                .as("lock must be acquired outside the style transaction")
                .isNull();
        assertThat(inner).isNotNull();
        assertThat(inner.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void twoChaptersWithNoSavedStyleAnalyzeOnce() throws Exception {
        AtomicReference<IllustrationSettings> committedStyle = new AtomicReference<>();
        AtomicBoolean styleVisibleToReaders = new AtomicBoolean(false);
        when(bookRepository.findById("book-1")).thenAnswer(invocation ->
                Optional.of(snapshotBook("book-1", styleVisibleToReaders.get() ? committedStyle.get() : null)));
        when(bookRepository.save(any(BookEntity.class))).thenAnswer(invocation -> {
            BookEntity saved = invocation.getArgument(0);
            committedStyle.set(styleFrom(saved));
            return saved;
        });
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of());

        AtomicInteger analyzeCalls = new AtomicInteger();
        when(styleAnalysisService.analyzeBookForStyle(any(), any(), any())).thenAnswer(invocation -> {
            analyzeCalls.incrementAndGet();
            Thread.sleep(40);
            return IllustrationSettings.defaults();
        });

        CountDownLatch firstReturned = new CountDownLatch(1);
        CountDownLatch commitSignal = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<IllustrationSettings> first = callers.submit(() -> {
                TransactionSynchronizationManager.initSynchronization();
                try {
                    IllustrationSettings style = service.getOrAnalyzeBookStyle("book-1", false);
                    firstReturned.countDown();
                    if (!commitSignal.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to commit style");
                    }
                    styleVisibleToReaders.set(true);
                    TransactionSynchronizationUtils.triggerAfterCommit();
                    TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
                    return style;
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                }
            });

            assertThat(firstReturned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(analyzeCalls.get()).isEqualTo(1);
            assertThat(snapshotBook("book-1", null).getIllustrationStyle()).isNull();

            Future<IllustrationSettings> second = callers.submit(() -> {
                TransactionSynchronizationManager.initSynchronization();
                try {
                    return service.getOrAnalyzeBookStyle("book-1", false);
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                }
            });

            Thread.sleep(200);
            assertThat(analyzeCalls.get())
                    .as("second reader must wait for afterCommit, not analyze against pre-commit findById")
                    .isEqualTo(1);
            assertThat(second.isDone())
                    .as("style lock must still be held until afterCommit")
                    .isFalse();

            commitSignal.countDown();
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(first.get(5, TimeUnit.SECONDS));
            assertThat(analyzeCalls.get()).isEqualTo(1);
            verify(styleAnalysisService, times(1)).analyzeBookForStyle(any(), any(), any());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void existingStyleDoesNotReanalyze() {
        BookEntity book = book("book-1", true);
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));

        IllustrationSettings first = service.getOrAnalyzeBookStyle("book-1", false);
        IllustrationSettings second = service.getOrAnalyzeBookStyle("book-1", false);

        assertThat(first.style()).isEqualTo("vintage book illustration");
        assertThat(second.style()).isEqualTo("vintage book illustration");
        verify(styleAnalysisService, never()).analyzeBookForStyle(any(), any(), any());
    }

    @Test
    void differentBooksAnalyzeStyleInParallel() throws Exception {
        BookEntity bookA = book("book-a", false);
        BookEntity bookB = book("book-b", false);
        when(bookRepository.findById("book-a")).thenReturn(Optional.of(bookA));
        when(bookRepository.findById("book-b")).thenReturn(Optional.of(bookB));
        when(chapterRepository.findByBookIdOrderByChapterIndex(anyString())).thenReturn(List.of());

        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(styleAnalysisService.analyzeBookForStyle(any(), any(), any())).thenAnswer(invocation -> {
            bothStarted.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("style analysis release timed out");
            }
            return IllustrationSettings.defaults();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> service.getOrAnalyzeBookStyle("book-a", false));
            pool.submit(() -> service.getOrAnalyzeBookStyle("book-b", false));
            assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(styleAnalysisService, timeout(2000).times(2)).analyzeBookForStyle(any(), any(), any());
    }

    @Test
    void cacheOnlyWorkersDoNotGenerate() throws Exception {
        ReflectionTestUtils.setField(service, "cacheOnly", true);
        ChapterEntity chapter = chapter("chapter-1", 1);
        service.init();
        when(illustrationRepository.findByChapterBookIdAndStatus("book-1", IllustrationStatus.PENDING))
                .thenReturn(List.of(new IllustrationEntity(chapter)));
        assertThat(service.forceQueuePendingForBook("book-1")).isEqualTo(0);
        Thread.sleep(200);
        verify(illustrationImageGenerator, never()).generateIllustration(any(), any(), any(), any());
        verify(illustrationRepository, never()).claimGenerationLease(any(), any(), any(), any(), any(), any());
    }

    private void stubChapterGeneration(ChapterEntity chapter) {
        String chapterId = chapter.getId();
        when(illustrationRepository.claimGenerationLease(
                eq(chapterId), any(), any(), any(),
                eq(IllustrationStatus.PENDING), eq(IllustrationStatus.GENERATING)))
                .thenReturn(1);
        when(chapterRepository.findByIdWithBook(chapterId)).thenReturn(Optional.of(chapter));
        when(comfyUIService.hasImage(anyString())).thenReturn(false);
        when(bookRepository.findById(chapter.getBook().getId())).thenReturn(Optional.of(chapter.getBook()));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex(chapterId)).thenReturn(List.of());
        when(promptService.generatePromptForChapter(any(), any(), any(), any(), any(), any()))
                .thenReturn("a chapter scene");
        when(portraitReferences.castForChapter(any(), any(), any())).thenReturn(List.of());
        when(portraitReferences.load(any())).thenReturn(List.of());
        when(portraitReferences.select(any(), any(), any())).thenReturn(List.of());
        when(illustrationRepository.findByChapterId(chapterId))
                .thenReturn(Optional.of(new IllustrationEntity(chapter)));
    }

    private static ChapterEntity chapter(String chapterId, int index) {
        return chapter(book("book-1", true), chapterId, index);
    }

    private static ChapterEntity chapter(BookEntity book, String chapterId, int index) {
        ChapterEntity chapter = new ChapterEntity(index, "Chapter " + index);
        chapter.setId(chapterId);
        chapter.setBook(book);
        return chapter;
    }

    private static BookEntity book(String bookId, boolean withStyle) {
        BookEntity book = new BookEntity();
        book.setId(bookId);
        book.setTitle("Pride and Prejudice");
        book.setAuthor("Jane Austen");
        book.setSource("gutenberg");
        book.setSourceId("1342");
        if (withStyle) {
            book.setIllustrationStyle("vintage book illustration");
            book.setIllustrationPromptPrefix("vintage,");
        }
        return book;
    }

    private static BookEntity snapshotBook(String bookId, IllustrationSettings style) {
        BookEntity snapshot = book(bookId, false);
        if (style != null) {
            snapshot.setIllustrationStyle(style.style());
            snapshot.setIllustrationPromptPrefix(style.promptPrefix());
            snapshot.setIllustrationSetting(style.setting());
            snapshot.setIllustrationStyleReasoning(style.reasoning());
            snapshot.setIllustrationCoverSubject(style.coverSubject());
            snapshot.setIllustrationCoverFocus(style.coverFocus());
        }
        return snapshot;
    }

    private static IllustrationSettings styleFrom(BookEntity book) {
        return new IllustrationSettings(
                book.getIllustrationStyle(),
                book.getIllustrationPromptPrefix(),
                book.getIllustrationSetting(),
                book.getIllustrationStyleReasoning(),
                book.getIllustrationCoverSubject(),
                book.getIllustrationCoverFocus()
        );
    }
}
