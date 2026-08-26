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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterServicePortraitCacheTest {

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
    private CharacterEntity character;

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
        ReflectionTestUtils.setField(service, "workerId", "test-worker");
        ReflectionTestUtils.setField(service, "portraitLeaseMinutes", 20);

        BookEntity book = new BookEntity();
        book.setId("book-1");
        book.setTitle("Pride and Prejudice");
        book.setSource("gutenberg");
        book.setSourceId("1342");

        ChapterEntity chapter = new ChapterEntity();
        chapter.setId("chapter-1");
        chapter.setBook(book);
        chapter.setChapterIndex(1);

        character = new CharacterEntity(book, "Mr. Bennet", "Elizabeth's father", chapter, 0);
        character.setId("character-1");
        character.setStatus(CharacterStatus.FAILED);
        character.setErrorMessage("Connection refused");
        character.setRetryCount(3);
    }

    @Test
    void generationRestoresStableCachedPortraitWithoutCallingComfyUi() throws Exception {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        when(characterRepository.claimPortraitLease(
                eq("character-1"), any(), any(), eq("test-worker"),
                eq(CharacterStatus.PENDING), eq(CharacterStatus.GENERATING)))
                .thenReturn(1);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(cacheKey)).thenReturn(true);
        when(characterRepository.claimCachedPortraitRestore(
                eq("character-1"), eq(cacheKey), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED)))
                .thenReturn(1);

        ReflectionTestUtils.invokeMethod(service, "generatePortrait", "character-1");

        assertEquals(CharacterStatus.COMPLETED, character.getStatus());
        assertEquals(cacheKey, character.getPortraitFilename());
        assertEquals(0, character.getRetryCount());
        assertNull(character.getErrorMessage());
        verify(characterRepository).claimCachedPortraitRestore(
                eq("character-1"), eq(cacheKey), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED));
        verify(comfyUIService, never()).submitPortraitWorkflow(any(), any(), any());
        verify(portraitService, never()).generatePortraitPrompt(any(), any(), any(), any(), any());
    }

    @Test
    void requestPortrait_pendingDirectedPrompt_doesNotRestoreCacheOrQueue() {
        character.setStatus(CharacterStatus.PENDING);
        character.setPortraitPrompt("Mr. Bennet in a dark coat");
        character.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));

        service.requestPortrait("character-1");

        assertEquals(CharacterStatus.PENDING, character.getStatus());
        assertEquals(CharacterEntity.DIRECTED_PORTRAIT_MARKER, character.getPortraitFilename());
        assertEquals(0, service.getQueueDepth());
        verify(comfyUIService, never()).hasPortraitImage(any());
        verify(characterRepository, never()).save(character);
    }

    @Test
    void requestPortrait_queuesOneCharacterWithoutPrefetch() {
        character.setStatus(CharacterStatus.PENDING);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(any())).thenReturn(false);

        service.requestPortrait("character-1");

        assertEquals(1, service.getQueueDepth());
    }

    @Test
    void regeneratePortraitWithPrompt_resetsLiveSlotAndQueuesCustomPrompt() {
        character.setStatus(CharacterStatus.COMPLETED);
        character.setPortraitFilename("books/gutenberg/1342/portraits/characters/mr-bennet.png");
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.claimPortraitRegeneration(
                eq("character-1"),
                eq("Mr. Bennet in a dark coat"),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER),
                eq(CharacterStatus.PENDING),
                eq(CharacterStatus.COMPLETED),
                eq(CharacterStatus.FAILED)))
                .thenReturn(1);

        assertEquals(true, service.regeneratePortraitWithPrompt("character-1", "Mr. Bennet in a dark coat"));
        assertEquals(1, service.getQueueDepth());
        verify(characterRepository).claimPortraitRegeneration(
                "character-1",
                "Mr. Bennet in a dark coat",
                CharacterEntity.DIRECTED_PORTRAIT_MARKER,
                CharacterStatus.PENDING,
                CharacterStatus.COMPLETED,
                CharacterStatus.FAILED);
    }

    @Test
    void regeneratePortraitWithPrompt_queuesOnlyAfterTransactionCommits() {
        character.setStatus(CharacterStatus.COMPLETED);
        character.setPortraitFilename("books/gutenberg/1342/portraits/characters/mr-bennet.png");
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.claimPortraitRegeneration(
                eq("character-1"),
                eq("Mr. Bennet in a dark coat"),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER),
                eq(CharacterStatus.PENDING),
                eq(CharacterStatus.COMPLETED),
                eq(CharacterStatus.FAILED)))
                .thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(true, service.regeneratePortraitWithPrompt("character-1", "Mr. Bennet in a dark coat"));

            assertEquals(0, service.getQueueDepth());

            List<TransactionSynchronization> syncs =
                    List.copyOf(TransactionSynchronizationManager.getSynchronizations());
            assertEquals(1, syncs.size());
            syncs.getFirst().afterCommit();

            assertEquals(1, service.getQueueDepth());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void regeneratePortraitWithPrompt_overlongPrompt_doesNotQueue() {
        character.setStatus(CharacterStatus.COMPLETED);
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));

        assertEquals(false, service.regeneratePortraitWithPrompt(
                "character-1",
                "x".repeat(CharacterEntity.PORTRAIT_PROMPT_MAX_LENGTH + 1)));

        assertEquals(0, service.getQueueDepth());
        verify(characterRepository, never()).claimPortraitRegeneration(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void regeneratePortraitWithPrompt_alreadyPending_doesNotResetOrQueue() {
        character.setStatus(CharacterStatus.PENDING);
        character.setPortraitPrompt("first custom prompt");
        character.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));

        assertEquals(false, service.regeneratePortraitWithPrompt("character-1", "second custom prompt"));

        assertEquals(CharacterStatus.PENDING, character.getStatus());
        assertEquals("first custom prompt", character.getPortraitPrompt());
        assertEquals(0, service.getQueueDepth());
        verify(characterRepository, never()).claimPortraitRegeneration(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void regeneratePortraitWithPrompt_alreadyGenerating_doesNotResetOrQueue() {
        character.setStatus(CharacterStatus.GENERATING);
        character.setPortraitFilename("books/gutenberg/1342/portraits/characters/mr-bennet.png");
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));

        assertEquals(false, service.regeneratePortraitWithPrompt("character-1", "Mr. Bennet in a dark coat"));

        assertEquals(CharacterStatus.GENERATING, character.getStatus());
        assertEquals("books/gutenberg/1342/portraits/characters/mr-bennet.png", character.getPortraitFilename());
        assertEquals(0, service.getQueueDepth());
        verify(characterRepository, never()).claimPortraitRegeneration(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void regeneratePortraitWithPrompt_lostAtomicClaim_doesNotQueue() {
        character.setStatus(CharacterStatus.COMPLETED);
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.claimPortraitRegeneration(
                eq("character-1"),
                eq("Mr. Bennet in a dark coat"),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER),
                eq(CharacterStatus.PENDING),
                eq(CharacterStatus.COMPLETED),
                eq(CharacterStatus.FAILED)))
                .thenReturn(0);

        assertEquals(false, service.regeneratePortraitWithPrompt("character-1", "Mr. Bennet in a dark coat"));
        assertEquals(0, service.getQueueDepth());
    }

    @Test
    void requestPortrait_failedAutoGenerateWithStoredPrompt_queuesRetry() {
        character.setStatus(CharacterStatus.FAILED);
        character.setPortraitPrompt("auto-generated prompt");
        character.setPortraitFilename(null);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(any())).thenReturn(false);
        when(characterRepository.claimFailedAutoPortraitRetry(
                "character-1",
                CharacterStatus.FAILED,
                CharacterStatus.PENDING,
                CharacterEntity.DIRECTED_PORTRAIT_MARKER))
                .thenReturn(1);

        service.requestPortrait("character-1");

        assertEquals(CharacterStatus.PENDING, character.getStatus());
        assertEquals(1, service.getQueueDepth());
        verify(characterRepository).claimFailedAutoPortraitRetry(
                "character-1",
                CharacterStatus.FAILED,
                CharacterStatus.PENDING,
                CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        verify(characterRepository, never()).save(character);
    }

    @Test
    void requestPortrait_failedDirected_requeuesStoredPrompt() {
        character.setStatus(CharacterStatus.FAILED);
        character.setPortraitPrompt("Mr. Bennet in a dark coat");
        character.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.claimFailedDirectedPortraitRetry(
                "character-1",
                CharacterStatus.FAILED,
                CharacterStatus.PENDING,
                CharacterEntity.DIRECTED_PORTRAIT_MARKER))
                .thenReturn(1);

        service.requestPortrait("character-1");

        assertEquals(CharacterStatus.PENDING, character.getStatus());
        assertEquals(CharacterEntity.DIRECTED_PORTRAIT_MARKER, character.getPortraitFilename());
        assertEquals(1, service.getQueueDepth());
        Object queued = ReflectionTestUtils.getField(service, "requestQueue");
        Object request = ((java.util.concurrent.BlockingQueue<?>) queued).peek();
        assertEquals("character-1", ReflectionTestUtils.getField(request, "characterId"));
        assertEquals("Mr. Bennet in a dark coat", ReflectionTestUtils.getField(request, "customPrompt"));
        verify(comfyUIService, never()).hasPortraitImage(any());
        verify(characterRepository, never()).claimCachedPortraitRestore(any(), any(), any(), any(), any());
    }

    @Test
    void requestPortrait_staleCompletedDoesNotRestoreOverDirectedClaim() {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        character.setStatus(CharacterStatus.COMPLETED);
        character.setPortraitFilename(cacheKey);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(cacheKey)).thenReturn(true);
        when(characterRepository.claimCachedPortraitRestore(
                eq("character-1"), eq(cacheKey), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED)))
                .thenReturn(0);

        service.requestPortrait("character-1");

        assertEquals(0, service.getQueueDepth());
        verify(characterRepository, never()).save(character);
        verify(characterRepository).claimCachedPortraitRestore(
                eq("character-1"), eq(cacheKey), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED));
    }

    @Test
    void generatePortrait_customPrompt_skipsCacheRestoreAndPromptLlm() throws Exception {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        when(characterRepository.claimPortraitLease(
                eq("character-1"), any(), any(), eq("test-worker"),
                eq(CharacterStatus.PENDING), eq(CharacterStatus.GENERATING)))
                .thenReturn(1);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(portraitImageGenerator.generatePortrait(
                eq("Mr. Bennet in a dark coat"), eq("portrait_character-1"), eq(cacheKey)))
                .thenReturn(cacheKey);

        ReflectionTestUtils.invokeMethod(service, "generatePortrait", "character-1", "Mr. Bennet in a dark coat");

        verify(portraitService, never()).generatePortraitPrompt(any(), any(), any(), any(), any());
        verify(comfyUIService, never()).hasPortraitImage(any());
        verify(portraitImageGenerator).generatePortrait(
                "Mr. Bennet in a dark coat", "portrait_character-1", cacheKey);
    }

    @Test
    void generatePortrait_storedPromptWithoutFilename_skipsCacheRestoreOnRecovery() throws Exception {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        character.setPortraitPrompt("Mr. Bennet in a dark coat");
        character.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        character.setStatus(CharacterStatus.PENDING);
        when(characterRepository.claimPortraitLease(
                eq("character-1"), any(), any(), eq("test-worker"),
                eq(CharacterStatus.PENDING), eq(CharacterStatus.GENERATING)))
                .thenReturn(1);
        when(characterRepository.findByIdWithBookAndChapter("character-1")).thenReturn(Optional.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(characterRepository.findById("character-1")).thenReturn(Optional.of(character));
        when(portraitImageGenerator.generatePortrait(
                eq("Mr. Bennet in a dark coat"), eq("portrait_character-1"), eq(cacheKey)))
                .thenReturn(cacheKey);

        ReflectionTestUtils.invokeMethod(service, "generatePortrait", "character-1");

        verify(portraitService, never()).generatePortraitPrompt(any(), any(), any(), any(), any());
        verify(comfyUIService, never()).hasPortraitImage(any());
        verify(portraitImageGenerator).generatePortrait(
                "Mr. Bennet in a dark coat", "portrait_character-1", cacheKey);
    }

    @Test
    void secondaryCharacterIsNotChatEligibleWhenBookHasNoPrimary() {
        character.setCharacterType(com.classicchatreader.entity.CharacterType.SECONDARY);
        assertEquals(false, service.isChatEligible(character));
    }

    @Test
    void secondaryCharacterIsNotChatEligibleWhenPrimaryExists() {
        character.setCharacterType(com.classicchatreader.entity.CharacterType.SECONDARY);
        assertEquals(false, service.isChatEligible(character));
    }

    @Test
    void startupRecoverySkipsCachedPortraitWhenDirectedPromptIsPending() {
        character.setStatus(CharacterStatus.PENDING);
        character.setPortraitPrompt("Mr. Bennet in a dark coat");
        character.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.GENERATING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of(character));
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of());

        int recovered = service.resetAndRequeueStuckPortraitsForBook("book-1");

        assertEquals(1, recovered);
        assertEquals(CharacterStatus.PENDING, character.getStatus());
        assertEquals(CharacterEntity.DIRECTED_PORTRAIT_MARKER, character.getPortraitFilename());
        assertEquals(1, service.getQueueDepth());
        verify(comfyUIService, never()).hasPortraitImage(any());
    }

    @Test
    void startupRecoverySkipsCachedPortraitWhenDirectedPromptIsGenerating() {
        character.setStatus(CharacterStatus.GENERATING);
        character.setPortraitPrompt("Mr. Bennet in a dark coat");
        character.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.GENERATING))
                .thenReturn(List.of(character));
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of());

        int recovered = service.resetAndRequeueStuckPortraitsForBook("book-1");

        assertEquals(1, recovered);
        assertEquals(CharacterStatus.PENDING, character.getStatus());
        assertEquals(CharacterEntity.DIRECTED_PORTRAIT_MARKER, character.getPortraitFilename());
        assertEquals("Mr. Bennet in a dark coat", character.getPortraitPrompt());
        assertEquals(1, service.getQueueDepth());
        Object queued = ReflectionTestUtils.getField(service, "requestQueue");
        Object request = ((java.util.concurrent.BlockingQueue<?>) queued).peek();
        assertEquals("character-1", ReflectionTestUtils.getField(request, "characterId"));
        assertEquals("Mr. Bennet in a dark coat", ReflectionTestUtils.getField(request, "customPrompt"));
        verify(comfyUIService, never()).hasPortraitImage(any());
        verify(characterRepository).save(character);
    }

    @Test
    void startupRecovery_generatingDirected_enqueuesOnlyAfterCommit() {
        character.setStatus(CharacterStatus.GENERATING);
        character.setPortraitPrompt("Mr. Bennet in a dark coat");
        character.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.GENERATING))
                .thenReturn(List.of(character));
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            int recovered = service.resetAndRequeueStuckPortraitsForBook("book-1");

            assertEquals(1, recovered);
            assertEquals(CharacterStatus.PENDING, character.getStatus());
            assertEquals(0, service.getQueueDepth());

            List<TransactionSynchronization> syncs =
                    List.copyOf(TransactionSynchronizationManager.getSynchronizations());
            assertEquals(1, syncs.size());
            syncs.getFirst().afterCommit();

            assertEquals(1, service.getQueueDepth());
            Object queued = ReflectionTestUtils.getField(service, "requestQueue");
            Object request = ((java.util.concurrent.BlockingQueue<?>) queued).peek();
            assertEquals("character-1", ReflectionTestUtils.getField(request, "characterId"));
            assertEquals("Mr. Bennet in a dark coat", ReflectionTestUtils.getField(request, "customPrompt"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void retryFailedPortraitsForBook_directed_enqueuesOnlyAfterCommit() {
        character.setStatus(CharacterStatus.FAILED);
        character.setPortraitPrompt("Mr. Bennet in a dark coat");
        character.setPortraitFilename(CharacterEntity.DIRECTED_PORTRAIT_MARKER);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of(character));

        TransactionSynchronizationManager.initSynchronization();
        try {
            int queued = service.retryFailedPortraitsForBook("book-1");

            assertEquals(1, queued);
            assertEquals(CharacterStatus.PENDING, character.getStatus());
            assertEquals(CharacterEntity.DIRECTED_PORTRAIT_MARKER, character.getPortraitFilename());
            assertEquals(0, service.getQueueDepth());

            List<TransactionSynchronization> syncs =
                    List.copyOf(TransactionSynchronizationManager.getSynchronizations());
            assertEquals(1, syncs.size());
            syncs.getFirst().afterCommit();

            assertEquals(1, service.getQueueDepth());
            Object queuedRequest = ((java.util.concurrent.BlockingQueue<?>)
                    ReflectionTestUtils.getField(service, "requestQueue")).peek();
            assertEquals("character-1", ReflectionTestUtils.getField(queuedRequest, "characterId"));
            assertEquals("Mr. Bennet in a dark coat", ReflectionTestUtils.getField(queuedRequest, "customPrompt"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void startupRecoveryRestoresCacheForPendingAutoPromptWithoutDirectedMarker() {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        character.setStatus(CharacterStatus.PENDING);
        character.setPortraitPrompt("auto-generated prompt that previously failed");
        character.setPortraitFilename(null);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.GENERATING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of(character));
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(cacheKey)).thenReturn(true);
        when(characterRepository.claimCachedPortraitRestore(
                eq("character-1"), eq(cacheKey), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED)))
                .thenReturn(1);

        int recovered = service.resetAndRequeueStuckPortraitsForBook("book-1");

        assertEquals(1, recovered);
        assertEquals(CharacterStatus.COMPLETED, character.getStatus());
        assertEquals(cacheKey, character.getPortraitFilename());
        verify(characterRepository).claimCachedPortraitRestore(
                eq("character-1"), eq(cacheKey), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED));
        verify(characterRepository, never()).save(character);
    }

    @Test
    void startupRecoveryRestoresFailedPortraitInCacheOnlyMode() throws Exception {
        String cacheKey = "books/gutenberg/1342/portraits/characters/mr-bennet.png";
        ReflectionTestUtils.setField(service, "cacheOnly", true);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.GENERATING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(cacheKey)).thenReturn(true);
        when(characterRepository.claimCachedPortraitRestore(
                eq("character-1"), eq(cacheKey), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED)))
                .thenReturn(1);

        int recovered = service.resetAndRequeueStuckPortraitsForBook("book-1");

        assertEquals(1, recovered);
        assertEquals(CharacterStatus.COMPLETED, character.getStatus());
        assertEquals(cacheKey, character.getPortraitFilename());
        assertNull(character.getErrorMessage());
        verify(characterRepository).claimCachedPortraitRestore(
                eq("character-1"), eq(cacheKey), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED));
        verify(characterRepository, never()).save(character);
        verify(comfyUIService, never()).submitPortraitWorkflow(any(), any(), any());
    }

    @Test
    void startupRecoveryRestoresUniqueHonorificAndSurnameAlias() throws Exception {
        character.setName("Mr. Bingley");
        String expectedKey = "books/gutenberg/1342/portraits/characters/mr-bingley.png";
        String cachedAlias = "books/gutenberg/1342/portraits/characters/mr-charles-bingley.png";
        ReflectionTestUtils.setField(service, "cacheOnly", true);
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.GENERATING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.PENDING))
                .thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus("book-1", CharacterStatus.FAILED))
                .thenReturn(List.of(character));
        when(characterRepository.findByBookIdOrderByCreatedAt("book-1")).thenReturn(List.of(character));
        when(comfyUIService.hasPortraitImage(expectedKey)).thenReturn(false);
        when(comfyUIService.listPortraitImages("books/gutenberg/1342/portraits/characters"))
                .thenReturn(List.of(
                        "books/gutenberg/1342/portraits/characters/caroline-bingley.png",
                        cachedAlias
                ));
        when(characterRepository.claimCachedPortraitRestore(
                eq("character-1"), eq(cachedAlias), any(),
                eq(CharacterEntity.DIRECTED_PORTRAIT_MARKER), eq(CharacterStatus.COMPLETED)))
                .thenReturn(1);

        int recovered = service.resetAndRequeueStuckPortraitsForBook("book-1");

        assertEquals(1, recovered);
        assertEquals(CharacterStatus.COMPLETED, character.getStatus());
        assertEquals(cachedAlias, character.getPortraitFilename());
        assertNull(character.getErrorMessage());
    }
}
