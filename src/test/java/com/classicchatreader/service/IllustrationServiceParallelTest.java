package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.IllustrationEntity;
import com.classicchatreader.entity.IllustrationStatus;
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
class IllustrationServiceParallelTest {

    @Mock private IllustrationRepository illustrationRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private BookRepository bookRepository;
    @Mock private ParagraphRepository paragraphRepository;
    @Mock private IllustrationPromptService promptService;
    @Mock private IllustrationStyleAnalysisService styleAnalysisService;
    @Mock private ComfyUIService comfyUIService;
    @Mock private IllustrationImageGeneratorService illustrationImageGenerator;
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
        when(illustrationImageGenerator.generateIllustration(anyString(), anyString(), anyString()))
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
                .generateIllustration(anyString(), anyString(), anyString());
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
        when(illustrationImageGenerator.generateIllustration(anyString(), anyString(), anyString()))
                .thenReturn("books/gutenberg/1342/illustrations/chapters/1.png");

        service.init();
        IllustrationEntity pending = new IllustrationEntity(chapter);
        when(illustrationRepository.findByChapterBookIdAndStatus("book-1", IllustrationStatus.PENDING))
                .thenReturn(List.of(pending, pending));
        service.forceQueuePendingForBook("book-1");

        verify(illustrationImageGenerator, timeout(2000).times(1))
                .generateIllustration(anyString(), anyString(), anyString());
        verify(illustrationRepository, times(2)).claimGenerationLease(
                eq("chapter-1"), any(), any(), any(),
                eq(IllustrationStatus.PENDING), eq(IllustrationStatus.GENERATING));
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
        verify(illustrationImageGenerator, never()).generateIllustration(any(), any(), any());
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
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(chapter.getBook()));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex(chapterId)).thenReturn(List.of());
        when(promptService.generatePromptForChapter(any(), any(), any(), any(), any()))
                .thenReturn("a chapter scene");
        when(illustrationRepository.findByChapterId(chapterId))
                .thenReturn(Optional.of(new IllustrationEntity(chapter)));
    }

    private static ChapterEntity chapter(String chapterId, int index) {
        BookEntity book = new BookEntity();
        book.setId("book-1");
        book.setTitle("Pride and Prejudice");
        book.setAuthor("Jane Austen");
        book.setSource("gutenberg");
        book.setSourceId("1342");
        book.setIllustrationStyle("vintage book illustration");
        book.setIllustrationPromptPrefix("vintage,");

        ChapterEntity chapter = new ChapterEntity(index, "Chapter " + index);
        chapter.setId(chapterId);
        chapter.setBook(book);
        return chapter;
    }
}
