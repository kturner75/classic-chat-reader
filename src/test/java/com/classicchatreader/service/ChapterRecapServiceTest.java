package com.classicchatreader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ChapterRecapEntity;
import com.classicchatreader.entity.ChapterRecapStatus;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.ChapterRecapPayload;
import com.classicchatreader.model.ChapterRecapResponse;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ChapterRecapRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterRecapServiceTest {

    @Mock
    private ChapterRecapRepository chapterRecapRepository;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private ParagraphRepository paragraphRepository;

    @Mock
    private CharacterRepository characterRepository;

    @Mock
    private LlmProvider reasoningProvider;

    @Mock
    private RecapMetricsService recapMetricsService;

    private ChapterRecapService chapterRecapService;

    @BeforeEach
    void setUp() {
        chapterRecapService = new ChapterRecapService(
                chapterRecapRepository,
                chapterRepository,
                paragraphRepository,
                characterRepository,
                reasoningProvider,
                recapMetricsService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(chapterRecapService, "workerId", "test-worker");
    }

    @Test
    void getChapterRecap_withoutStoredRecap_returnsMissingPayload() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        when(chapterRecapRepository.findByChapterIdWithChapterAndBook("chapter-1"))
                .thenReturn(Optional.empty());
        when(chapterRepository.findByIdWithBook("chapter-1"))
                .thenReturn(Optional.of(chapter));

        Optional<ChapterRecapResponse> result = chapterRecapService.getChapterRecap("chapter-1");

        assertTrue(result.isPresent());
        ChapterRecapResponse recap = result.get();
        assertEquals("book-1", recap.bookId());
        assertEquals("chapter-1", recap.chapterId());
        assertEquals("MISSING", recap.status());
        assertFalse(recap.ready());
        assertEquals("", recap.payload().shortSummary());
        assertEquals(List.of(), recap.payload().keyEvents());
        assertEquals(List.of(), recap.payload().characterDeltas());
    }

    @Test
    void getChapterRecap_withStoredRecap_returnsStructuredPayload() throws Exception {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        ChapterRecapEntity entity = new ChapterRecapEntity(chapter);
        entity.setStatus(ChapterRecapStatus.COMPLETED);
        entity.setGeneratedAt(LocalDateTime.of(2026, 2, 8, 12, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 2, 8, 12, 1));
        entity.setPromptVersion("v1");
        entity.setModelName("grok");
        entity.setPayloadJson(new ObjectMapper().writeValueAsString(new ChapterRecapPayload(
                "Summary",
                List.of("Event one"),
                List.of(new ChapterRecapPayload.CharacterDelta("Ishmael", "Grows more reflective"))
        )));
        when(chapterRecapRepository.findByChapterIdWithChapterAndBook("chapter-1"))
                .thenReturn(Optional.of(entity));

        Optional<ChapterRecapResponse> result = chapterRecapService.getChapterRecap("chapter-1");

        assertTrue(result.isPresent());
        ChapterRecapResponse recap = result.get();
        assertEquals("COMPLETED", recap.status());
        assertTrue(recap.ready());
        assertEquals("Summary", recap.payload().shortSummary());
        assertEquals("Event one", recap.payload().keyEvents().get(0));
        assertEquals("Ishmael", recap.payload().characterDeltas().get(0).characterName());
    }

    @Test
    void saveGeneratedRecap_persistsCompletedRecord() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRecapRepository.findByChapterId("chapter-1")).thenReturn(Optional.empty());
        when(chapterRecapRepository.save(any(ChapterRecapEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        chapterRecapService.saveGeneratedRecap(
                "chapter-1",
                new ChapterRecapPayload(
                        "Summary",
                        List.of("Event one"),
                        List.of(new ChapterRecapPayload.CharacterDelta("Ishmael", "Grows more reflective"))
                ),
                "v1",
                "grok"
        );

        ArgumentCaptor<ChapterRecapEntity> captor = ArgumentCaptor.forClass(ChapterRecapEntity.class);
        verify(chapterRecapRepository).save(captor.capture());
        ChapterRecapEntity saved = captor.getValue();
        assertEquals(ChapterRecapStatus.COMPLETED, saved.getStatus());
        assertNotNull(saved.getGeneratedAt());
        assertEquals("v1", saved.getPromptVersion());
        assertEquals("grok", saved.getModelName());
        assertNotNull(saved.getPayloadJson());
        assertTrue(saved.getPayloadJson().contains("Summary"));
    }

    @Test
    void saveGeneratedRecap_missingChapter_throws() {
        when(chapterRepository.findById("missing")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> chapterRecapService.saveGeneratedRecap("missing", null, "v1", "grok")
        );
        assertTrue(ex.getMessage().contains("Chapter not found"));
    }

    @Test
    void requestChapterRecap_existingPendingRecap_requeuesAsPending() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        ChapterRecapEntity recap = new ChapterRecapEntity(chapter);
        recap.setStatus(ChapterRecapStatus.FAILED);

        when(chapterRecapRepository.findByChapterId("chapter-1")).thenReturn(Optional.of(recap));

        chapterRecapService.requestChapterRecap("chapter-1");

        ArgumentCaptor<ChapterRecapEntity> captor = ArgumentCaptor.forClass(ChapterRecapEntity.class);
        verify(chapterRecapRepository).save(captor.capture());
        assertEquals(ChapterRecapStatus.PENDING, captor.getValue().getStatus());
    }

    @Test
    void requestChapterRecap_existingGeneratingRecap_recentlyUpdated_doesNotRequeue() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        ChapterRecapEntity recap = new ChapterRecapEntity(chapter);
        recap.setStatus(ChapterRecapStatus.GENERATING);
        recap.setUpdatedAt(LocalDateTime.now());

        ReflectionTestUtils.setField(chapterRecapService, "stuckThresholdMinutes", 15);
        when(chapterRecapRepository.findByChapterId("chapter-1")).thenReturn(Optional.of(recap));

        chapterRecapService.requestChapterRecap("chapter-1");

        verify(chapterRecapRepository, never()).save(any(ChapterRecapEntity.class));
        verify(recapMetricsService, never()).recordGenerationRequested();
    }

    @Test
    void requestChapterRecap_existingGeneratingRecap_stale_requeuesAsPending() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        ChapterRecapEntity recap = new ChapterRecapEntity(chapter);
        recap.setStatus(ChapterRecapStatus.GENERATING);
        recap.setUpdatedAt(LocalDateTime.now().minusMinutes(30));

        ReflectionTestUtils.setField(chapterRecapService, "stuckThresholdMinutes", 15);
        when(chapterRecapRepository.findByChapterId("chapter-1")).thenReturn(Optional.of(recap));

        chapterRecapService.requestChapterRecap("chapter-1");

        ArgumentCaptor<ChapterRecapEntity> captor = ArgumentCaptor.forClass(ChapterRecapEntity.class);
        verify(chapterRecapRepository).save(captor.capture());
        assertEquals(ChapterRecapStatus.PENDING, captor.getValue().getStatus());
        verify(recapMetricsService).recordGenerationRequested();
    }

    @Test
    void processChapterRecap_withAvailableProvider_persistsLlmGeneratedPayload() {
        ReflectionTestUtils.setField(chapterRecapService, "maxContextChars", 4000);
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.getProviderName()).thenReturn("xai");
        when(chapterRecapRepository.claimGenerationLease(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                {
                  "shortSummary": "Holmes explains the case.",
                  "keyEvents": ["A clue is revealed.", "Watson notes the change in mood."],
                  "characterDeltas": [{"characterName": "Holmes", "delta": "Takes control of the investigation."}]
                }
                """);

        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRecapRepository.findByChapterId("chapter-1")).thenReturn(Optional.empty());
        when(chapterRecapRepository.save(any(ChapterRecapEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ParagraphEntity paragraph = new ParagraphEntity(0, "Holmes studies the clue and explains his reasoning.");
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1")).thenReturn(List.of(paragraph));
        when(characterRepository.findByBookIdUpToChapter("book-1", 1)).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(chapterRecapService, "processChapterRecap", "chapter-1");

        ArgumentCaptor<ChapterRecapEntity> captor = ArgumentCaptor.forClass(ChapterRecapEntity.class);
        verify(chapterRecapRepository).save(captor.capture());
        ChapterRecapEntity saved = captor.getValue();
        assertEquals(ChapterRecapStatus.COMPLETED, saved.getStatus());
        assertEquals("v2-llm-json", saved.getPromptVersion());
        assertEquals("xai", saved.getModelName());
        assertNotNull(saved.getPayloadJson());
        assertTrue(saved.getPayloadJson().contains("Holmes explains the case."));
    }

    @Test
    void processChapterRecap_cacheOnlyMode_skipsQueuedGeneration() {
        ReflectionTestUtils.setField(chapterRecapService, "cacheOnly", true);

        ReflectionTestUtils.invokeMethod(chapterRecapService, "processChapterRecap", "chapter-1");

        verify(reasoningProvider, never()).generate(any(), any());
        verify(chapterRecapRepository, never()).claimGenerationLease(any(), any(), any(), any(), any(), any());
        verify(chapterRepository, never()).findByIdWithBook("chapter-1");
        verify(chapterRecapRepository, never()).save(any(ChapterRecapEntity.class));
    }

    @Test
    void processChapterRecap_llmFailure_fallsBackToLocalRecap() {
        ReflectionTestUtils.setField(chapterRecapService, "maxContextChars", 4000);
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(chapterRecapRepository.claimGenerationLease(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(reasoningProvider.generate(any(), any())).thenThrow(new RuntimeException("Ollama 500"));

        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(chapterRecapRepository.findByChapterId("chapter-1")).thenReturn(Optional.empty());
        when(chapterRecapRepository.save(any(ChapterRecapEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1"))
                .thenReturn(List.of(
                        new ParagraphEntity(0, "First event happens in the chapter."),
                        new ParagraphEntity(1, "Second event moves the plot forward.")
                ));
        when(characterRepository.findByBookIdUpToChapter("book-1", 1)).thenReturn(List.of());
        when(characterRepository.findByBookIdAndFirstChapterIdOrderByFirstParagraphIndex("book-1", "chapter-1"))
                .thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(chapterRecapService, "processChapterRecap", "chapter-1");

        ArgumentCaptor<ChapterRecapEntity> captor = ArgumentCaptor.forClass(ChapterRecapEntity.class);
        verify(chapterRecapRepository).save(captor.capture());
        ChapterRecapEntity saved = captor.getValue();
        assertEquals(ChapterRecapStatus.COMPLETED, saved.getStatus());
        assertEquals("v1-extractive", saved.getPromptVersion());
        assertEquals("local-extractive", saved.getModelName());
        assertNotNull(saved.getPayloadJson());
        assertFalse(saved.getPayloadJson().isBlank());
    }

    @Test
    void processChapterRecap_whenLeaseClaimFails_skipsGeneration() {
        when(chapterRecapRepository.claimGenerationLease(any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        ReflectionTestUtils.invokeMethod(chapterRecapService, "processChapterRecap", "chapter-1");

        verify(chapterRepository, never()).findByIdWithBook("chapter-1");
        verify(reasoningProvider, never()).generate(any(), any());
        verify(chapterRecapRepository, never()).save(any(ChapterRecapEntity.class));
    }

    @Test
    void processChapterRecap_failureBeforeMaxRetries_requeuesAsPending() {
        ReflectionTestUtils.setField(chapterRecapService, "maxRetryAttempts", 3);
        ReflectionTestUtils.setField(chapterRecapService, "initialRetryDelaySeconds", 30);
        ReflectionTestUtils.setField(chapterRecapService, "maxRetryDelaySeconds", 300);

        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        ChapterRecapEntity recap = new ChapterRecapEntity(chapter);
        recap.setStatus(ChapterRecapStatus.GENERATING);
        recap.setRetryCount(0);

        when(chapterRecapRepository.claimGenerationLease(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1"))
                .thenThrow(new RuntimeException("paragraph store unavailable"));
        when(chapterRecapRepository.findByChapterId("chapter-1")).thenReturn(Optional.of(recap));
        when(chapterRecapRepository.save(any(ChapterRecapEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(chapterRecapService, "processChapterRecap", "chapter-1");

        ArgumentCaptor<ChapterRecapEntity> captor = ArgumentCaptor.forClass(ChapterRecapEntity.class);
        verify(chapterRecapRepository).save(captor.capture());
        ChapterRecapEntity saved = captor.getValue();
        assertEquals(ChapterRecapStatus.PENDING, saved.getStatus());
        assertEquals(1, saved.getRetryCount());
        assertNotNull(saved.getNextRetryAt());
        verify(recapMetricsService).recordGenerationFailed(anyLong());
    }

    @Test
    void processChapterRecap_failureAtMaxRetries_marksFailed() {
        ReflectionTestUtils.setField(chapterRecapService, "maxRetryAttempts", 3);

        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1, "Chapter 1");
        ChapterRecapEntity recap = new ChapterRecapEntity(chapter);
        recap.setStatus(ChapterRecapStatus.GENERATING);
        recap.setRetryCount(2);

        when(chapterRecapRepository.claimGenerationLease(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("chapter-1"))
                .thenThrow(new RuntimeException("paragraph store unavailable"));
        when(chapterRecapRepository.findByChapterId("chapter-1")).thenReturn(Optional.of(recap));
        when(chapterRecapRepository.save(any(ChapterRecapEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(chapterRecapService, "processChapterRecap", "chapter-1");

        ArgumentCaptor<ChapterRecapEntity> captor = ArgumentCaptor.forClass(ChapterRecapEntity.class);
        verify(chapterRecapRepository).save(captor.capture());
        ChapterRecapEntity saved = captor.getValue();
        assertEquals(ChapterRecapStatus.FAILED, saved.getStatus());
        assertEquals(3, saved.getRetryCount());
        assertNull(saved.getNextRetryAt());
        verify(recapMetricsService).recordGenerationFailed(anyLong());
    }

    private ChapterEntity createChapter(String bookId, String chapterId, int index, String title) {
        BookEntity book = new BookEntity("Title", "Author", "gutenberg");
        book.setId(bookId);

        ChapterEntity chapter = new ChapterEntity(index, title);
        chapter.setId(chapterId);
        chapter.setBook(book);
        return chapter;
    }
}
