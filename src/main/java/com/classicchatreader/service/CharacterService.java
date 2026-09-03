package com.classicchatreader.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.classicchatreader.entity.*;
import com.classicchatreader.model.CharacterInfo;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CharacterService {

    private static final Logger log = LoggerFactory.getLogger(CharacterService.class);
    private static final Set<String> NAME_TITLES = Set.of(
            "mr", "mrs", "ms", "miss", "lady", "lord", "sir", "madam", "madame",
            "mme", "mlle", "dr", "doctor", "prof", "professor", "rev", "reverend",
            "capt", "captain", "col", "colonel", "major"
    );

    private final CharacterRepository characterRepository;
    private final ChapterAnalysisRepository chapterAnalysisRepository;
    private final ChapterRepository chapterRepository;
    private final BookRepository bookRepository;
    private final ParagraphRepository paragraphRepository;
    private final CharacterExtractionService extractionService;
    private final CharacterPortraitService portraitService;
    private final IllustrationService illustrationService;
    private final ComfyUIService comfyUIService;
    private final CharacterPortraitImageGeneratorService portraitImageGenerator;
    private final AssetKeyService assetKeyService;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    @Value("${character.analysis.lease-minutes:15}")
    private int analysisLeaseMinutes;

    @Value("${character.portrait.lease-minutes:20}")
    private int portraitLeaseMinutes;

    @Value("${character.generation.worker-id:}")
    private String configuredWorkerId;

    @Value("${generation.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${generation.retry.initial-delay-seconds:30}")
    private int initialRetryDelaySeconds;

    @Value("${generation.retry.max-delay-seconds:600}")
    private int maxRetryDelaySeconds;

    @Value("${generation.imagine.max-in-flight:4}")
    private int imagineMaxInFlight;

    private String workerId;
    private int imagineWorkerCount = ImagineInFlightLimiter.DEFAULT_MAX_IN_FLIGHT;

    private final BlockingQueue<CharacterRequest> requestQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor();
    private ExecutorService dispatcher;
    private ExecutorService analysisExecutor;
    private ExecutorService portraitExecutor;
    private volatile boolean running = true;

    private CharacterService self;

    public CharacterService(
            CharacterRepository characterRepository,
            ChapterAnalysisRepository chapterAnalysisRepository,
            ChapterRepository chapterRepository,
            BookRepository bookRepository,
            ParagraphRepository paragraphRepository,
            CharacterExtractionService extractionService,
            CharacterPortraitService portraitService,
            IllustrationService illustrationService,
            ComfyUIService comfyUIService,
            CharacterPortraitImageGeneratorService portraitImageGenerator,
            AssetKeyService assetKeyService) {
        this.characterRepository = characterRepository;
        this.chapterAnalysisRepository = chapterAnalysisRepository;
        this.chapterRepository = chapterRepository;
        this.bookRepository = bookRepository;
        this.paragraphRepository = paragraphRepository;
        this.extractionService = extractionService;
        this.portraitService = portraitService;
        this.illustrationService = illustrationService;
        this.comfyUIService = comfyUIService;
        this.portraitImageGenerator = portraitImageGenerator;
        this.assetKeyService = assetKeyService;
    }

    @Autowired
    @Lazy
    public void setSelf(CharacterService self) {
        this.self = self;
    }

    @PostConstruct
    public void init() {
        workerId = (configuredWorkerId != null && !configuredWorkerId.isBlank())
                ? configuredWorkerId
                : "character-" + UUID.randomUUID();
        imagineWorkerCount = Math.max(1, imagineMaxInFlight);
        dispatcher = Executors.newSingleThreadExecutor(workerThreadFactory("character-dispatch"));
        analysisExecutor = Executors.newSingleThreadExecutor(workerThreadFactory("character-analysis"));
        portraitExecutor = Executors.newFixedThreadPool(imagineWorkerCount, workerThreadFactory("character-portrait"));
        dispatcher.submit(this::dispatchQueue);
        log.info(
                "Character service started with {} Imagine portrait workers and a dedicated analysis path (workerId={})",
                imagineWorkerCount,
                workerId
        );
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (dispatcher != null) {
            dispatcher.shutdownNow();
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
        }
        if (portraitExecutor != null) {
            portraitExecutor.shutdownNow();
        }
        retryScheduler.shutdownNow();
        log.info("Character service shutting down");
    }

    public boolean isAvailable() {
        return extractionService.isReasoningProviderAvailable() && portraitImageGenerator.isAvailable();
    }

    public boolean isQueueProcessorRunning() {
        return dispatcher != null && !dispatcher.isShutdown() && !dispatcher.isTerminated();
    }

    public int getImagineWorkerCount() {
        return imagineWorkerCount;
    }

    public int getQueueDepth() {
        return requestQueue.size();
    }

    @Transactional
    public void requestChapterAnalysis(String chapterId) {
        if (cacheOnly) {
            log.info("Skipping character analysis request in cache-only mode for chapter {}", chapterId);
            return;
        }
        Optional<ChapterAnalysisEntity> existingAnalysis = chapterAnalysisRepository.findByChapterId(chapterId);
        if (existingAnalysis.isPresent()) {
            ChapterAnalysisEntity analysis = existingAnalysis.get();
            ChapterAnalysisStatus status = analysis.getStatus();
            if (status == null) {
                status = analysis.getCharacterCount() > 0
                        ? ChapterAnalysisStatus.COMPLETED
                        : ChapterAnalysisStatus.PENDING;
                analysis.setStatus(status);
                if (status != ChapterAnalysisStatus.GENERATING) {
                    clearAnalysisLease(analysis);
                }
                chapterAnalysisRepository.save(analysis);
            }
            if (status == ChapterAnalysisStatus.COMPLETED) {
                log.debug("Chapter {} already analyzed for characters", chapterId);
                return;
            }
            if (status == ChapterAnalysisStatus.GENERATING) {
                log.debug("Chapter {} character analysis already in progress", chapterId);
                return;
            }
            // Pending or failed: re-queue to avoid gaps after restarts.
            analysis.setStatus(ChapterAnalysisStatus.PENDING);
            analysis.setRetryCount(0);
            analysis.setNextRetryAt(null);
            clearAnalysisLease(analysis);
            chapterAnalysisRepository.save(analysis);
            boolean queued = requestQueue.offer(new AnalysisRequest(chapterId));
            if (queued) {
                log.info("Re-queued character analysis for chapter: {}", chapterId);
            } else {
                log.error("Failed to re-queue character analysis for chapter: {}", chapterId);
            }
            return;
        }

        ChapterEntity chapter = chapterRepository.findById(chapterId).orElse(null);
        if (chapter == null) {
            log.warn("Cannot analyze chapter: not found: {}", chapterId);
            return;
        }

        try {
            ChapterAnalysisEntity analysis = new ChapterAnalysisEntity(chapter);
            chapterAnalysisRepository.save(analysis);

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    boolean queued = requestQueue.offer(new AnalysisRequest(chapterId));
                    if (queued) {
                        log.info("Queued character analysis for chapter: {}", chapterId);
                    } else {
                        log.error("Failed to queue character analysis for chapter: {}", chapterId);
                    }
                }
            });
        } catch (DataIntegrityViolationException e) {
            log.debug("Chapter {} already being analyzed (race condition handled)", chapterId);
        }
    }

    public void prefetchNextChapter(String currentChapterId) {
        if (cacheOnly) {
            log.info("Skipping character prefetch in cache-only mode for chapter {}", currentChapterId);
            return;
        }
        ChapterEntity current = chapterRepository.findById(currentChapterId).orElse(null);
        if (current == null) return;

        chapterRepository.findByBookIdAndChapterIndex(
                current.getBook().getId(),
                current.getChapterIndex() + 1
        ).ifPresent(next -> {
            log.debug("Pre-fetching character analysis for next chapter: {}", next.getTitle());
            self.requestChapterAnalysis(next.getId());
        });
    }

    public List<CharacterInfo> getCharactersForBook(String bookId) {
        return toChatAwareInfos(bookId, characterRepository.findByBookIdOrderByCreatedAt(bookId));
    }

    public List<CharacterInfo> getCharactersUpToPosition(String bookId, int chapterIndex, int paragraphIndex) {
        return toChatAwareInfos(
                bookId,
                characterRepository.findByBookIdUpToPosition(bookId, chapterIndex, paragraphIndex)
        );
    }

    public List<CharacterInfo> getNewlyCompletedSince(String bookId, LocalDateTime sinceTime) {
        return toChatAwareInfos(bookId, characterRepository.findNewlyCompletedSince(bookId, sinceTime));
    }

    public Optional<CharacterEntity> getCharacter(String characterId) {
        return characterRepository.findById(characterId);
    }

    public CharacterInfo toChatAwareInfo(CharacterEntity character) {
        if (character == null) {
            return null;
        }
        return CharacterInfo.from(character).withChatEligible(isChatEligible(character));
    }

    /**
     * Chat and call are PRIMARY only. An empty PRIMARY list means nobody to call —
     * SECONDARY characters are never a fallback.
     */
    public boolean isChatEligible(CharacterEntity character) {
        return character != null && character.getCharacterType() == CharacterType.PRIMARY;
    }

    /**
     * Operator overlay for studio apply: type and/or first appearance.
     * Omitted fields stay unchanged. Explicit PRIMARY→SECONDARY is allowed here;
     * the prefetch refresh lock does not apply to this write.
     * <p>
     * {@code firstChapter} stays NOT NULL: an unknown {@code firstChapterIndex}
     * is rejected instead of clearing the chapter. When only the chapter changes,
     * paragraph defaults to 0.
     */
    @Transactional
    public CharacterInfo patchCharacter(
            String characterId,
            String characterType,
            Integer firstChapterIndex,
            Integer firstParagraphIndex) {
        if (cacheOnly) {
            log.info("Skipping character patch in cache-only mode for character {}", characterId);
            throw new IllegalStateException("Character roster cannot be patched in cache-only mode");
        }

        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));

        CharacterType parsedType = parseOperatorCharacterType(characterType);
        if (firstParagraphIndex != null && firstParagraphIndex < 0) {
            throw new IllegalArgumentException("firstParagraphIndex must be >= 0");
        }

        ChapterEntity newChapter = null;
        if (firstChapterIndex != null) {
            String bookId = character.getBook().getId();
            newChapter = chapterRepository.findByBookIdAndChapterIndex(bookId, firstChapterIndex)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown firstChapterIndex " + firstChapterIndex + " for this book"));
        }

        if (parsedType != null) {
            character.setCharacterType(parsedType);
        }
        if (newChapter != null) {
            character.setFirstChapter(newChapter);
            character.setFirstParagraphIndex(firstParagraphIndex != null ? firstParagraphIndex : 0);
        } else if (firstParagraphIndex != null) {
            character.setFirstParagraphIndex(firstParagraphIndex);
        }

        characterRepository.save(character);
        log.info(
                "Operator patched character {} type={} firstChapterIndex={} firstParagraphIndex={}",
                characterId,
                character.getCharacterType(),
                character.getFirstChapter().getChapterIndex(),
                character.getFirstParagraphIndex()
        );
        return toChatAwareInfo(character);
    }

    private CharacterType parseOperatorCharacterType(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Invalid characterType");
        }
        try {
            return CharacterType.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid characterType: " + raw);
        }
    }

    private List<CharacterInfo> toChatAwareInfos(String bookId, List<CharacterEntity> characters) {
        return characters.stream()
                .map(character -> CharacterInfo.from(character).withChatEligible(
                        character.getCharacterType() == CharacterType.PRIMARY))
                .collect(Collectors.toList());
    }

    public CharacterStatus getPortraitStatus(String characterId) {
        return characterRepository.findById(characterId)
                .map(CharacterEntity::getStatus)
                .orElse(null);
    }

    public byte[] getPortrait(String characterId) {
        return characterRepository.findById(characterId)
                .filter(c -> c.getStatus() == CharacterStatus.COMPLETED)
                .map(c -> comfyUIService.getPortraitImage(c.getPortraitFilename()))
                .orElse(null);
    }

    /**
     * Replace live portrait bytes and prompt metadata without enqueueing generation.
     * {@code source} is accepted for studio keep/restore; portraits have no source
     * column on main, so only the prompt is persisted.
     */
    @Transactional
    public LiveAssetWriteResult saveUploadedPortrait(
            String characterId,
            byte[] imageData,
            String source,
            String generatedPrompt,
            String promptOverride) {
        if (cacheOnly) {
            log.info("Skipping portrait upload in cache-only mode for character {}", characterId);
            return LiveAssetWriteResult.CACHE_ONLY;
        }
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId).orElse(null);
        if (character == null) {
            return LiveAssetWriteResult.NOT_FOUND;
        }
        if (character.getStatus() == CharacterStatus.GENERATING) {
            log.info("Rejecting portrait upload for character {} while generation is active", characterId);
            return LiveAssetWriteResult.GENERATION_IN_PROGRESS;
        }
        PngImages.requirePng(imageData, "Portrait uploads must be PNG images.");
        String cacheKey = buildPortraitCacheKey(character);
        String filename;
        try {
            filename = comfyUIService.savePortraitImage(cacheKey, imageData);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unable to save portrait: " + e.getMessage(), e);
        }

        character.setPortraitFilename(filename);
        character.setStatus(CharacterStatus.COMPLETED);
        character.setRetryCount(0);
        character.setNextRetryAt(null);
        character.setErrorMessage(null);
        character.setCompletedAt(LocalDateTime.now());
        String storedPrompt = LiveAssetUploads.resolveStoredPrompt(generatedPrompt, promptOverride);
        if (storedPrompt != null) {
            character.setPortraitPrompt(storedPrompt);
        }
        clearCharacterLease(character);
        characterRepository.save(character);
        log.info("Saved uploaded portrait for character {} (source={})", characterId, LiveAssetUploads.resolveSource(source));
        return LiveAssetWriteResult.SAVED;
    }

    public Optional<String> getPortraitFilename(String characterId) {
        return characterRepository.findById(characterId)
                .filter(c -> c.getStatus() == CharacterStatus.COMPLETED)
                .map(CharacterEntity::getPortraitFilename);
    }

    private void dispatchQueue() {
        log.info("Character queue dispatcher thread started");
        int dispatchedCount = 0;
        while (running) {
            try {
                log.debug("Waiting for character request in queue...");
                CharacterRequest request = requestQueue.take();
                if (cacheOnly) {
                    if (request instanceof AnalysisRequest ar) {
                        log.info("Skipping queued character analysis in cache-only mode for chapter {}", ar.chapterId());
                    } else if (request instanceof PortraitRequest pr) {
                        log.info("Skipping queued portrait generation in cache-only mode for character {}", pr.characterId());
                    }
                    continue;
                }
                dispatchedCount++;

                if (request instanceof AnalysisRequest ar) {
                    log.info("Dispatching character analysis #{} for chapter: {}", dispatchedCount, ar.chapterId());
                    analysisExecutor.submit(() -> {
                        try {
                            processChapterAnalysis(ar.chapterId());
                        } catch (Exception e) {
                            log.error("Error processing character analysis for chapter {}", ar.chapterId(), e);
                        }
                    });
                } else if (request instanceof PortraitRequest pr) {
                    log.info("Dispatching portrait generation #{} for character: {}", dispatchedCount, pr.characterId());
                    portraitExecutor.submit(() -> {
                        try {
                            generatePortrait(pr.characterId(), pr.customPrompt());
                        } catch (Exception e) {
                            log.error("Error processing portrait generation for character {}", pr.characterId(), e);
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Character queue dispatcher thread interrupted");
                break;
            } catch (Exception e) {
                log.error("Error dispatching character queue", e);
            }
        }
        log.info("Character queue dispatcher thread stopped after dispatching {} requests", dispatchedCount);
    }

    private void processChapterAnalysis(String chapterId) {
        if (cacheOnly) {
            log.info("Skipping chapter analysis in cache-only mode for chapter {}", chapterId);
            return;
        }
        if (!tryClaimAnalysisLease(chapterId)) {
            log.debug("Skipping character analysis for chapter {} because lease claim failed", chapterId);
            rescheduleDeferredAnalysisRetryIfNeeded(chapterId);
            return;
        }
        ChapterEntity chapter = chapterRepository.findByIdWithBook(chapterId).orElse(null);
        if (chapter == null) {
            log.warn("Chapter not found for analysis: {}", chapterId);
            self.handleAnalysisFailure(chapterId, false);
            return;
        }

        BookEntity book = chapter.getBook();
        List<CharacterEntity> trusted = characterRepository.findByBookIdWithFirstChapterOrderByCreatedAt(book.getId());

        try {
            // Chapter text is only for first-appearance of names we already trust
            // (the prefetch PRIMARY list, plus any existing roster row). Never invent
            // new SECONDARY names from the miner — empty SECONDARY is preferred to animals/objects.
            if (trusted.isEmpty()) {
                log.info("No trusted roster for '{}'; chapter miner will not invent names", book.getTitle());
                self.updateChapterAnalysisCount(chapterId, 0);
                return;
            }
            int updated = refineTrustedFirstAppearances(chapter, trusted);
            log.info("Chapter miner refined {} trusted first-appearances in '{}' / '{}'; invented 0 names",
                    updated, book.getTitle(), chapter.getTitle());
            self.updateChapterAnalysisCount(chapterId, 0);
        } catch (Exception e) {
            log.error("Failed to analyze chapter {}", chapterId, e);
            self.handleAnalysisFailure(chapterId, true);
        }
    }

    /**
     * Scan this chapter for already-trusted roster names and pull first-appearance
     * earlier when the text shows they appear here first. Does not insert rows.
     */
    private int refineTrustedFirstAppearances(ChapterEntity chapter, List<CharacterEntity> trusted) {
        List<ParagraphEntity> paragraphs = paragraphRepository.findByChapterIdOrderByParagraphIndex(chapter.getId());
        if (paragraphs.isEmpty()) {
            return 0;
        }
        int updated = 0;
        int chapterIndex = chapter.getChapterIndex();
        for (CharacterEntity character : trusted) {
            int foundParagraph = -1;
            for (ParagraphEntity paragraph : paragraphs) {
                if (CharacterRosterNameFilter.appearsInText(character.getName(), paragraph.getContent())) {
                    foundParagraph = paragraph.getParagraphIndex();
                    break;
                }
            }
            if (foundParagraph < 0) {
                continue;
            }
            // firstChapter is initialized by findByBookIdWithFirstChapterOrderByCreatedAt
            // before this queue-thread method runs; do not lazy-load it here.
            ChapterEntity storedChapter = character.getFirstChapter();
            int storedChapterIndex = storedChapter != null ? storedChapter.getChapterIndex() : Integer.MAX_VALUE;
            boolean earlierChapter = chapterIndex < storedChapterIndex;
            boolean earlierParagraph = storedChapter != null
                    && storedChapter.getId() != null
                    && storedChapter.getId().equals(chapter.getId())
                    && foundParagraph < character.getFirstParagraphIndex();
            if (earlierChapter || earlierParagraph) {
                character.setFirstChapter(chapter);
                character.setFirstParagraphIndex(foundParagraph);
                characterRepository.save(character);
                updated++;
            }
        }
        return updated;
    }

    @Transactional
    public CharacterEntity createCharacter(BookEntity book, ChapterEntity chapter,
                                           String name, String description, int paragraphIndex) {
        Optional<CharacterEntity> existing = characterRepository.findByBookIdAndNameIgnoreCase(book.getId(), name);
        if (existing.isPresent()) {
            log.debug("Character '{}' already exists for book '{}'", name, book.getTitle());
            return null;
        }

        if (isNameVariantOfExisting(book.getId(), name)) {
            log.debug("Character '{}' appears to be a variant of an existing name for '{}'", name, book.getTitle());
            return null;
        }

        CharacterEntity character = new CharacterEntity(book, name, description, chapter, paragraphIndex);
        return characterRepository.save(character);
    }

    /**
     * Queue portrait generation for a character. Used by CharacterPrefetchService.
     */
    public void queuePortraitGeneration(String characterId) {
        if (cacheOnly) {
            log.info("Skipping portrait queue in cache-only mode for character {}", characterId);
            return;
        }
        CharacterEntity character = characterRepository.findById(characterId).orElse(null);
        if (character == null) {
            log.warn("Cannot queue portrait generation: character not found {}", characterId);
            return;
        }
        if (character.hasStoredPortraitImage()) {
            log.debug("Skipping portrait queue for {} - portrait file already present", characterId);
            return;
        }

        boolean queued = requestQueue.offer(new PortraitRequest(characterId));
        if (queued) {
            log.debug("Queued portrait generation for character: {}", characterId);
        } else {
            log.error("Failed to queue portrait generation for character: {}", characterId);
        }
    }

    /**
     * Request portrait generation for one existing character without prefetching the book roster.
     */
    @Transactional
    public void requestPortrait(String characterId) {
        if (cacheOnly) {
            log.info("Skipping portrait request in cache-only mode for character {}", characterId);
            return;
        }
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId).orElse(null);
        if (character == null) {
            log.warn("Cannot request portrait: character not found {}", characterId);
            return;
        }

        CharacterStatus status = character.getStatus();
        if (hasDirectedPortraitIntent(character)
                && (status == CharacterStatus.PENDING || status == CharacterStatus.GENERATING)) {
            log.debug("Skipping portrait request for character {} because a custom regeneration is already in flight",
                    characterId);
            return;
        }

        if (hasDirectedPortraitIntent(character) && status == CharacterStatus.FAILED) {
            int claimed = characterRepository.claimFailedDirectedPortraitRetry(
                    characterId,
                    CharacterStatus.FAILED,
                    CharacterStatus.PENDING,
                    CharacterEntity.DIRECTED_PORTRAIT_MARKER);
            if (claimed == 0) {
                log.debug("Skipping directed portrait retry for character {} because the slot was already claimed",
                        characterId);
                return;
            }
            markPortraitPendingForRetry(character);
            enqueuePortraitRequest(characterId, character.getPortraitPrompt());
            return;
        }

        String cacheKey = buildPortraitCacheKey(character);
        if (restoreCachedPortrait(character, cacheKey)) {
            return;
        }

        status = character.getStatus();
        if (status == CharacterStatus.GENERATING) {
            log.debug("Portrait already {} for character {}", status, characterId);
            return;
        }

        if (status == CharacterStatus.COMPLETED) {
            if (resolveCachedPortraitKey(character, cacheKey).isPresent()) {
                log.debug("Portrait already COMPLETED for character {}", characterId);
                return;
            }
            int claimed = characterRepository.claimMissingCompletedPortraitRetry(
                    characterId,
                    CharacterStatus.COMPLETED,
                    CharacterStatus.PENDING,
                    CharacterEntity.DIRECTED_PORTRAIT_MARKER);
            if (claimed == 0) {
                log.debug("Skipping missing-portrait retry for character {} because the slot was already claimed",
                        characterId);
                return;
            }
            markPortraitPendingForRetry(character);
            character.setPortraitFilename(null);
            character.setCompletedAt(null);
        } else if (status == CharacterStatus.FAILED) {
            int claimed = characterRepository.claimFailedAutoPortraitRetry(
                    characterId,
                    CharacterStatus.FAILED,
                    CharacterStatus.PENDING,
                    CharacterEntity.DIRECTED_PORTRAIT_MARKER);
            if (claimed == 0) {
                log.debug("Skipping failed portrait retry for character {} because the slot was already claimed",
                        characterId);
                return;
            }
            markPortraitPendingForRetry(character);
        }

        enqueuePortraitRequest(characterId, null);
    }

    /**
     * Reset one character's portrait and regenerate it with a custom prompt.
     */
    @Transactional
    public boolean regeneratePortraitWithPrompt(String characterId, String customPrompt) {
        if (cacheOnly) {
            log.info("Skipping portrait regeneration in cache-only mode for character {}", characterId);
            return false;
        }
        CharacterEntity character = characterRepository.findById(characterId).orElse(null);
        if (character == null) {
            log.warn("Cannot regenerate portrait: character not found {}", characterId);
            return false;
        }
        if (character.getStatus() == CharacterStatus.GENERATING
                || character.getStatus() == CharacterStatus.PENDING) {
            log.info("Skipping portrait regeneration for character {} because a job is already in progress",
                    characterId);
            return false;
        }
        if (customPrompt != null && customPrompt.length() > CharacterEntity.PORTRAIT_PROMPT_MAX_LENGTH) {
            log.warn("Skipping portrait regeneration for character {}: prompt exceeds {} characters",
                    characterId, CharacterEntity.PORTRAIT_PROMPT_MAX_LENGTH);
            return false;
        }

        int claimed = characterRepository.claimPortraitRegeneration(
                characterId,
                customPrompt,
                CharacterEntity.DIRECTED_PORTRAIT_MARKER,
                CharacterStatus.PENDING,
                CharacterStatus.COMPLETED,
                CharacterStatus.FAILED);
        if (claimed == 0) {
            log.info("Skipping portrait regeneration for character {} because the slot was already claimed",
                    characterId);
            return false;
        }

        String priorFilename = character.hasStoredPortraitImage()
                ? character.getPortraitFilename()
                : null;
        enqueuePortraitRequest(characterId, customPrompt, priorFilename);
        return true;
    }

    private void enqueuePortraitRequest(String characterId, String customPrompt) {
        enqueuePortraitRequest(characterId, customPrompt, null);
    }

    private void enqueuePortraitRequest(String characterId, String customPrompt, String priorPortraitFilename) {
        PortraitRequest request = new PortraitRequest(characterId, customPrompt);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deletePriorPortraitIfPresent(priorPortraitFilename);
                    offerPortraitRequest(request);
                }
            });
            return;
        }
        deletePriorPortraitIfPresent(priorPortraitFilename);
        offerPortraitRequest(request);
    }

    private void deletePriorPortraitIfPresent(String filename) {
        if (filename != null && !filename.isBlank()) {
            comfyUIService.deletePortraitFile(filename);
        }
    }

    private void offerPortraitRequest(PortraitRequest request) {
        boolean queued = requestQueue.offer(request);
        if (queued) {
            log.info("Queued portrait generation for character: {}", request.characterId());
        } else {
            log.error("Failed to queue portrait generation for character: {}", request.characterId());
        }
    }

    @Transactional
    public void updateChapterAnalysisCount(String chapterId, int count) {
        chapterAnalysisRepository.findByChapterId(chapterId).ifPresent(analysis -> {
            analysis.setCharacterCount(count);
            analysis.setAnalyzedAt(LocalDateTime.now());
            analysis.setStatus(ChapterAnalysisStatus.COMPLETED);
            analysis.setNextRetryAt(null);
            clearAnalysisLease(analysis);
            chapterAnalysisRepository.save(analysis);
        });
    }

    private boolean isNameVariantOfExisting(String bookId, String name) {
        String normalizedNew = normalizeName(name);
        if (normalizedNew.isEmpty()) {
            return true;
        }

        List<CharacterEntity> existingCharacters = characterRepository.findByBookIdOrderByCreatedAt(bookId);
        for (CharacterEntity existing : existingCharacters) {
            String normalizedExisting = normalizeName(existing.getName());
            if (normalizedExisting.isEmpty()) {
                continue;
            }
            if (normalizedExisting.equals(normalizedNew)) {
                return true;
            }
            if (isLastNameOnly(normalizedNew) && lastNameMatches(normalizedExisting, normalizedNew)) {
                return true;
            }
            if (isLastNameOnly(normalizedExisting) && lastNameMatches(normalizedNew, normalizedExisting)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.toLowerCase()
                .replaceAll("[^a-z\\s-]", " ")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        List<String> parts = Arrays.stream(cleaned.split(" "))
                .filter(part -> !part.isBlank())
                .collect(Collectors.toList());
        while (!parts.isEmpty() && NAME_TITLES.contains(parts.get(0))) {
            parts.remove(0);
        }
        return String.join(" ", parts).trim();
    }

    private boolean isLastNameOnly(String normalizedName) {
        if (normalizedName.isBlank()) {
            return false;
        }
        return normalizedName.split(" ").length == 1;
    }

    private boolean lastNameMatches(String normalizedA, String normalizedB) {
        String lastA = normalizedA.substring(normalizedA.lastIndexOf(' ') + 1);
        String lastB = normalizedB.substring(normalizedB.lastIndexOf(' ') + 1);
        return !lastA.isBlank() && lastA.equals(lastB);
    }

    @Transactional
    public void updateChapterAnalysisStatus(String chapterId, ChapterAnalysisStatus status) {
        chapterAnalysisRepository.findByChapterId(chapterId).ifPresent(analysis -> {
            analysis.setStatus(status);
            if (status != ChapterAnalysisStatus.PENDING) {
                analysis.setNextRetryAt(null);
            }
            if (status != ChapterAnalysisStatus.GENERATING) {
                clearAnalysisLease(analysis);
            }
            chapterAnalysisRepository.save(analysis);
        });
    }

    private void generatePortrait(String characterId) {
        generatePortrait(characterId, null);
    }

    private void generatePortrait(String characterId, String customPrompt) {
        if (!tryClaimPortraitLease(characterId)) {
            log.debug("Skipping portrait generation for character {} because lease claim failed", characterId);
            rescheduleDeferredPortraitRetryIfNeeded(characterId, customPrompt);
            return;
        }
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId).orElse(null);
        if (character == null) {
            log.warn("Character not found for portrait generation: {}", characterId);
            self.handlePortraitFailure(characterId, "Character not found", false, customPrompt);
            return;
        }

        String cacheKey = buildPortraitCacheKey(character);
        String effectivePrompt = resolveDirectedPortraitPrompt(character, customPrompt);
        if (effectivePrompt == null && restoreCachedPortrait(character, cacheKey)) {
            return;
        }
        if (cacheOnly) {
            log.info("Skipping portrait generation in cache-only mode for character {}", characterId);
            self.updateCharacterStatus(characterId, CharacterStatus.PENDING, null, null);
            return;
        }

        try {
            String portraitPrompt;
            if (effectivePrompt != null) {
                portraitPrompt = effectivePrompt;
            } else {
                BookEntity book = character.getBook();
                IllustrationSettings bookStyle = illustrationService.getOrAnalyzeBookStyle(book.getId(), false);
                portraitPrompt = portraitService.generatePortraitPrompt(
                        book.getTitle(),
                        book.getAuthor(),
                        character.getName(),
                        character.getDescription(),
                        bookStyle
                );
            }

            self.updatePortraitPrompt(characterId, portraitPrompt);

            String outputPrefix = "portrait_" + characterId;
            String filename = portraitImageGenerator.generatePortrait(portraitPrompt, outputPrefix, cacheKey);

            self.updateCharacterStatus(characterId, CharacterStatus.COMPLETED, filename, null);
            log.info("Portrait completed for character: {} via {}",
                    character.getName(), portraitImageGenerator.getProviderName());

        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                log.info("Portrait generation interrupted for character {}", characterId);
                self.updateCharacterStatus(characterId, CharacterStatus.PENDING, null, null);
                return;
            }
            log.error("Failed to generate portrait for character: {}", character.getName(), e);
            self.handlePortraitFailure(characterId, e.getMessage(), true, customPrompt);
        }
    }

    @Transactional
    public void updateCharacterStatus(String characterId, CharacterStatus status,
                                      String filename, String errorMessage) {
        CharacterEntity character = characterRepository.findById(characterId).orElse(null);
        if (character == null) {
            log.warn("Cannot update status: character not found {}", characterId);
            return;
        }
        character.setStatus(status);
        if (filename != null) {
            character.setPortraitFilename(filename);
        }
        if (errorMessage != null) {
            character.setErrorMessage(errorMessage);
        }
        if (status == CharacterStatus.COMPLETED) {
            character.setCompletedAt(LocalDateTime.now());
            character.setErrorMessage(null);
            character.setRetryCount(0);
        }
        if (status != CharacterStatus.PENDING) {
            character.setNextRetryAt(null);
        }
        if (status != CharacterStatus.GENERATING) {
            clearCharacterLease(character);
        }
        characterRepository.save(character);
        log.debug("Updated character status for {}: {}", characterId, status);
    }

    private String resolveDirectedPortraitPrompt(CharacterEntity character, String customPrompt) {
        if (customPrompt != null && !customPrompt.isBlank()) {
            return customPrompt;
        }
        if (hasDirectedPortraitIntent(character)) {
            return character.getPortraitPrompt();
        }
        return null;
    }

    private boolean hasDirectedPortraitIntent(CharacterEntity character) {
        return character.hasDirectedPortraitIntent()
                && character.getPortraitPrompt() != null
                && !character.getPortraitPrompt().isBlank();
    }

    private boolean restoreCachedPortrait(CharacterEntity character, String cacheKey) {
        String resolvedKey = resolveCachedPortraitKey(character, cacheKey).orElse(null);
        if (resolvedKey == null) {
            return false;
        }
        int claimed = characterRepository.claimCachedPortraitRestore(
                character.getId(),
                resolvedKey,
                LocalDateTime.now(),
                CharacterEntity.DIRECTED_PORTRAIT_MARKER,
                CharacterStatus.COMPLETED);
        if (claimed == 0) {
            log.debug("Skipping cached portrait restore for character {} because a directed job claimed the slot",
                    character.getId());
            return false;
        }
        markPortraitRestoredFromCache(character, resolvedKey);
        log.info("Restored cached portrait for character '{}' from {}", character.getName(), resolvedKey);
        return true;
    }

    private void markPortraitRestoredFromCache(CharacterEntity character, String filename) {
        character.setStatus(CharacterStatus.COMPLETED);
        character.setPortraitFilename(filename);
        character.setErrorMessage(null);
        character.setRetryCount(0);
        character.setCompletedAt(LocalDateTime.now());
        character.setNextRetryAt(null);
        clearCharacterLease(character);
    }

    private void markPortraitPendingForRetry(CharacterEntity character) {
        character.setStatus(CharacterStatus.PENDING);
        character.setErrorMessage(null);
        character.setRetryCount(0);
        character.setNextRetryAt(null);
        clearCharacterLease(character);
    }

    private Optional<String> resolveCachedPortraitKey(CharacterEntity character, String expectedKey) {
        if (comfyUIService.hasPortraitImage(expectedKey)) {
            return Optional.of(expectedKey);
        }

        String normalizedName = assetKeyService.normalizeCharacterName(character.getName());
        List<String> nameParts = Arrays.stream(normalizedName.split("-"))
                .filter(part -> !part.isBlank())
                .toList();
        if (nameParts.size() < 2 || !NAME_TITLES.contains(nameParts.get(0))) {
            return Optional.empty();
        }

        int finalSlash = expectedKey.lastIndexOf('/');
        if (finalSlash < 0) {
            return Optional.empty();
        }
        String directory = expectedKey.substring(0, finalSlash);
        String title = nameParts.get(0);
        String surname = nameParts.get(nameParts.size() - 1);
        List<String> matches = comfyUIService.listPortraitImages(directory).stream()
                .filter(candidate -> portraitKeyMatchesTitleAndSurname(candidate, title, surname))
                .toList();
        if (matches.size() == 1) {
            log.info("Matched portrait alias for character '{}': {}", character.getName(), matches.get(0));
            return Optional.of(matches.get(0));
        }
        if (matches.size() > 1) {
            log.warn("Skipping ambiguous cached portrait aliases for character '{}': {}", character.getName(), matches);
        }
        return Optional.empty();
    }

    private boolean portraitKeyMatchesTitleAndSurname(String key, String title, String surname) {
        int finalSlash = key.lastIndexOf('/');
        String filename = finalSlash >= 0 ? key.substring(finalSlash + 1) : key;
        String stem = filename.toLowerCase().endsWith(".png")
                ? filename.substring(0, filename.length() - 4)
                : filename;
        List<String> parts = Arrays.stream(stem.split("-"))
                .filter(part -> !part.isBlank())
                .toList();
        return parts.size() >= 2
                && title.equals(parts.get(0))
                && surname.equals(parts.get(parts.size() - 1));
    }

    @Transactional
    public void updatePortraitPrompt(String characterId, String prompt) {
        CharacterEntity character = characterRepository.findById(characterId).orElse(null);
        if (character == null) return;
        character.setPortraitPrompt(prompt);
        characterRepository.save(character);
    }

    private String getChapterText(String chapterId) {
        List<ParagraphEntity> paragraphs = paragraphRepository
                .findByChapterIdOrderByParagraphIndex(chapterId);

        return paragraphs.stream()
                .map(ParagraphEntity::getContent)
                .map(this::stripHtml)
                .collect(Collectors.joining("\n\n"));
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "").trim();
    }

    private String buildPortraitCacheKey(CharacterEntity character) {
        BookEntity book = character.getBook();
        String baseSlug = assetKeyService.normalizeCharacterName(character.getName());
        String normalizedSlug = baseSlug.isBlank() ? "character" : baseSlug;

        List<CharacterEntity> sameName = characterRepository.findByBookIdOrderByCreatedAt(book.getId());
        boolean collision = sameName.stream()
                .anyMatch(other -> !other.getId().equals(character.getId())
                        && assetKeyService.normalizeCharacterName(other.getName()).equals(normalizedSlug));

        String resolvedSlug = normalizedSlug;
        if (collision) {
            resolvedSlug = normalizedSlug + "-"
                    + character.getFirstChapter().getChapterIndex()
                    + "-"
                    + character.getFirstParagraphIndex();
        }

        return assetKeyService.buildPortraitKey(book, resolvedSlug);
    }

    /**
     * Delete all characters for a book and clean up portrait files.
     * Also clears chapter analysis records so they can be re-analyzed,
     * and resets {@code character_prefetch_completed} so PRIMARY prefetch runs again.
     *
     * @param bookId the book to clear characters for
     * @return number of characters deleted
     */
    @Transactional
    public int deleteCharactersForBook(String bookId) {
        List<CharacterEntity> characters = characterRepository.findByBookIdOrderByCreatedAt(bookId);

        // Delete portrait files first
        int deletedFiles = 0;
        for (CharacterEntity character : characters) {
            deletedFiles += deletePortraitAssets(character);
        }
        log.info("Deleted {} portrait files for book {}", deletedFiles, bookId);

        // Delete chapter analyses so chapters can be re-analyzed
        chapterAnalysisRepository.deleteByBookId(bookId);
        log.info("Deleted chapter analyses for book {}", bookId);

        // Delete characters from database
        int characterCount = characters.size();
        characterRepository.deleteByBookId(bookId);
        log.info("Deleted {} characters for book {}", characterCount, bookId);

        // Prefetch latches completed=true even for an empty usable answer. Leave the flag
        // set and a later DELETE + pregen no-ops PRIMARY prefetch, recreating SECONDARY junk.
        bookRepository.findById(bookId).ifPresent(book -> {
            book.setCharacterPrefetchCompleted(false);
            bookRepository.save(book);
            log.info("Cleared character prefetch latch for book {}", bookId);
        });

        return characterCount;
    }

    private int deletePortraitAssets(CharacterEntity character) {
        int deleted = 0;
        if (character.hasStoredPortraitImage()
                && comfyUIService.deletePortraitFile(character.getPortraitFilename())) {
            deleted++;
        }
        if (character.hasDirectedPortraitIntent() && character.getBook() != null) {
            String cacheKey = buildPortraitCacheKey(character);
            if (comfyUIService.deletePortraitFile(cacheKey)) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Force re-queue all pending portrait generation for a specific book.
     * Used by pre-generation to ensure all items get processed.
     */
    @Transactional(readOnly = true)
    public int forceQueuePendingPortraitsForBook(String bookId) {
        if (cacheOnly) {
            log.info("Skipping portrait re-queue in cache-only mode for book {}", bookId);
            return 0;
        }
        List<CharacterEntity> pendingCharacters = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.PENDING);
        int queued = 0;
        for (CharacterEntity character : pendingCharacters) {
            if (!character.hasStoredPortraitImage()) {
                if (requestQueue.offer(portraitRequestFromCharacter(character))) {
                    queued++;
                }
            }
        }
        log.info("Force-queued {} pending portraits for book {}", queued, bookId);
        return queued;
    }

    /**
     * Reset failed portraits back to PENDING and re-queue them.
     * Used by pre-generation to retry prior portrait failures.
     */
    @Transactional
    public int retryFailedPortraitsForBook(String bookId) {
        if (cacheOnly) {
            log.info("Skipping failed portrait retry in cache-only mode for book {}", bookId);
            return 0;
        }
        List<CharacterEntity> failedCharacters = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.FAILED);
        int queued = 0;
        for (CharacterEntity character : failedCharacters) {
            int claimed = hasDirectedPortraitIntent(character)
                    ? characterRepository.claimFailedDirectedPortraitRetry(
                            character.getId(),
                            CharacterStatus.FAILED,
                            CharacterStatus.PENDING,
                            CharacterEntity.DIRECTED_PORTRAIT_MARKER)
                    : characterRepository.claimFailedAutoPortraitRetry(
                            character.getId(),
                            CharacterStatus.FAILED,
                            CharacterStatus.PENDING,
                            CharacterEntity.DIRECTED_PORTRAIT_MARKER);
            if (claimed == 0) {
                continue;
            }
            markPortraitPendingForRetry(character);
            if (enqueueRecoveredPortrait(character)) {
                queued++;
            }
        }
        log.info("Reset {} failed portraits and queued {} for book {}", failedCharacters.size(), queued, bookId);
        return queued;
    }

    /**
     * Force re-queue all pending character analyses for a specific book.
     * Used by pre-generation to ensure all analyses get processed.
     */
    @Transactional(readOnly = true)
    public int forceQueuePendingAnalysesForBook(String bookId) {
        if (cacheOnly) {
            log.info("Skipping analysis re-queue in cache-only mode for book {}", bookId);
            return 0;
        }
        List<ChapterAnalysisEntity> pendingAnalyses = chapterAnalysisRepository
                .findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.PENDING);
        List<ChapterAnalysisEntity> nullStatusAnalyses = chapterAnalysisRepository
                .findByChapterBookIdAndStatusIsNull(bookId);
        int queued = 0;
        for (ChapterAnalysisEntity analysis : pendingAnalyses) {
            if (requestQueue.offer(new AnalysisRequest(analysis.getChapter().getId()))) {
                queued++;
            }
        }
        for (ChapterAnalysisEntity analysis : nullStatusAnalyses) {
            if (requestQueue.offer(new AnalysisRequest(analysis.getChapter().getId()))) {
                queued++;
            }
        }
        log.info("Force-queued {} pending character analyses for book {}", queued, bookId);
        return queued;
    }

    /**
     * Reset stuck GENERATING portraits back to PENDING and re-queue them.
     * Used when generation appears stalled.
     */
    @Transactional
    public int resetAndRequeueStuckPortraitsForBook(String bookId) {
        List<CharacterEntity> stuckGenerating = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.GENERATING);
        List<CharacterEntity> stuckPending = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.PENDING);
        List<CharacterEntity> failed = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.FAILED);

        int restored = 0;
        for (CharacterEntity character : java.util.stream.Stream
                .of(stuckGenerating, stuckPending, failed)
                .flatMap(List::stream)
                .toList()) {
            if (hasDirectedPortraitIntent(character)) {
                continue;
            }
            String cacheKey = buildPortraitCacheKey(character);
            if (restoreCachedPortrait(character, cacheKey)) {
                restored++;
            }
        }

        if (cacheOnly) {
            log.info("Restored {} cached portraits and skipped portrait reset/re-queue in cache-only mode for book {}",
                    restored, bookId);
            return restored;
        }

        int reset = 0;
        for (CharacterEntity character : stuckGenerating) {
            if (character.getStatus() == CharacterStatus.COMPLETED) {
                continue;
            }
            character.setStatus(CharacterStatus.PENDING);
            character.setRetryCount(0);
            character.setNextRetryAt(null);
            clearCharacterLease(character);
            characterRepository.save(character);
            reset++;
        }

        // Re-queue all pending (including just-reset ones) after the PENDING
        // status is committed so the worker does not see a stale GENERATING row.
        int queued = 0;
        for (CharacterEntity character : stuckGenerating) {
            if (enqueueRecoveredPortrait(character)) {
                queued++;
            }
        }
        for (CharacterEntity character : stuckPending) {
            if (enqueueRecoveredPortrait(character)) {
                queued++;
            }
        }

        log.info("Restored {} cached portraits, reset {} stuck GENERATING portraits, and queued {} total for book {}",
                restored, reset, queued, bookId);
        return restored + reset + (int) stuckPending.stream()
                .filter(character -> character.getStatus() != CharacterStatus.COMPLETED)
                .count();
    }

    /**
     * Reset stuck GENERATING chapter analyses back to PENDING and re-queue them.
     * Used when generation appears stalled.
     */
    @Transactional
    public int resetAndRequeueStuckAnalysesForBook(String bookId) {
        if (cacheOnly) {
            log.info("Skipping analysis reset/re-queue in cache-only mode for book {}", bookId);
            return 0;
        }
        List<ChapterAnalysisEntity> stuckGenerating = chapterAnalysisRepository
                .findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.GENERATING);
        List<ChapterAnalysisEntity> stuckPending = chapterAnalysisRepository
                .findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.PENDING);
        List<ChapterAnalysisEntity> nullStatusAnalyses = chapterAnalysisRepository
                .findByChapterBookIdAndStatusIsNull(bookId);

        int reset = 0;
        for (ChapterAnalysisEntity analysis : stuckGenerating) {
            analysis.setStatus(ChapterAnalysisStatus.PENDING);
            analysis.setRetryCount(0);
            analysis.setNextRetryAt(null);
            clearAnalysisLease(analysis);
            chapterAnalysisRepository.save(analysis);
            reset++;
        }

        int queued = 0;
        for (ChapterAnalysisEntity analysis : stuckGenerating) {
            if (requestQueue.offer(new AnalysisRequest(analysis.getChapter().getId()))) {
                queued++;
            }
        }
        for (ChapterAnalysisEntity analysis : stuckPending) {
            if (requestQueue.offer(new AnalysisRequest(analysis.getChapter().getId()))) {
                queued++;
            }
        }
        for (ChapterAnalysisEntity analysis : nullStatusAnalyses) {
            if (requestQueue.offer(new AnalysisRequest(analysis.getChapter().getId()))) {
                queued++;
            }
        }

        log.info("Reset {} stuck GENERATING analyses and queued {} total for book {}", reset, queued, bookId);
        return reset + stuckPending.size() + nullStatusAnalyses.size();
    }

    private sealed interface CharacterRequest permits AnalysisRequest, PortraitRequest {
    }

    private record AnalysisRequest(String chapterId) implements CharacterRequest {
    }

    private record PortraitRequest(String characterId, String customPrompt) implements CharacterRequest {
        PortraitRequest(String characterId) {
            this(characterId, null);
        }
    }

    private PortraitRequest portraitRequestFromCharacter(CharacterEntity character) {
        String storedPrompt = hasDirectedPortraitIntent(character) ? character.getPortraitPrompt() : null;
        return new PortraitRequest(character.getId(), storedPrompt);
    }

    private boolean enqueueRecoveredPortrait(CharacterEntity character) {
        if (character.getStatus() == CharacterStatus.COMPLETED || character.hasStoredPortraitImage()) {
            return false;
        }
        PortraitRequest request = portraitRequestFromCharacter(character);
        enqueuePortraitRequest(request.characterId(), request.customPrompt());
        return true;
    }

    private boolean tryClaimPortraitLease(String characterId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiresAt = now.plusMinutes(Math.max(1, portraitLeaseMinutes));
        int claimed = characterRepository.claimPortraitLease(
                characterId,
                now,
                leaseExpiresAt,
                workerId,
                CharacterStatus.PENDING,
                CharacterStatus.GENERATING
        );
        return claimed > 0;
    }

    private boolean tryClaimAnalysisLease(String chapterId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiresAt = now.plusMinutes(Math.max(1, analysisLeaseMinutes));
        int claimed = chapterAnalysisRepository.claimAnalysisLease(
                chapterId,
                now,
                leaseExpiresAt,
                workerId,
                ChapterAnalysisStatus.PENDING,
                ChapterAnalysisStatus.GENERATING
        );
        return claimed > 0;
    }

    private void clearCharacterLease(CharacterEntity character) {
        character.setLeaseOwner(null);
        character.setLeaseExpiresAt(null);
    }

    private void clearAnalysisLease(ChapterAnalysisEntity analysis) {
        analysis.setLeaseOwner(null);
        analysis.setLeaseExpiresAt(null);
    }

    @Transactional
    public void handleAnalysisFailure(String chapterId, boolean retryable) {
        ChapterAnalysisEntity analysis = chapterAnalysisRepository.findByChapterId(chapterId).orElse(null);
        if (analysis == null) {
            log.warn("Cannot record chapter analysis failure: analysis not found for chapter {}", chapterId);
            return;
        }

        int nextRetryCount = Math.max(0, analysis.getRetryCount()) + 1;
        analysis.setRetryCount(nextRetryCount);
        clearAnalysisLease(analysis);

        int configuredMaxAttempts = Math.max(1, maxRetryAttempts);
        if (retryable && nextRetryCount < configuredMaxAttempts) {
            long delayMs = computeRetryDelayMillis(nextRetryCount);
            LocalDateTime nextRetryAt = LocalDateTime.now().plus(Duration.ofMillis(delayMs));
            analysis.setStatus(ChapterAnalysisStatus.PENDING);
            analysis.setNextRetryAt(nextRetryAt);
            chapterAnalysisRepository.save(analysis);
            scheduleRetryRequest(new AnalysisRequest(chapterId), delayMs);
            log.warn(
                    "Retrying character analysis for chapter {} in {}s (attempt {}/{})",
                    chapterId,
                    Math.max(1L, delayMs / 1000L),
                    nextRetryCount + 1,
                    configuredMaxAttempts
            );
            return;
        }

        analysis.setStatus(ChapterAnalysisStatus.FAILED);
        analysis.setNextRetryAt(null);
        chapterAnalysisRepository.save(analysis);
    }

    @Transactional
    public void handlePortraitFailure(String characterId, String errorMessage, boolean retryable) {
        handlePortraitFailure(characterId, errorMessage, retryable, null);
    }

    @Transactional
    public void handlePortraitFailure(String characterId, String errorMessage, boolean retryable, String customPrompt) {
        CharacterEntity character = characterRepository.findById(characterId).orElse(null);
        if (character == null) {
            log.warn("Cannot record portrait failure: character not found {}", characterId);
            return;
        }

        int nextRetryCount = Math.max(0, character.getRetryCount()) + 1;
        character.setRetryCount(nextRetryCount);
        character.setErrorMessage(errorMessage);
        clearCharacterLease(character);

        int configuredMaxAttempts = Math.max(1, maxRetryAttempts);
        if (retryable && nextRetryCount < configuredMaxAttempts) {
            long delayMs = computeRetryDelayMillis(nextRetryCount);
            LocalDateTime nextRetryAt = LocalDateTime.now().plus(Duration.ofMillis(delayMs));
            character.setStatus(CharacterStatus.PENDING);
            character.setNextRetryAt(nextRetryAt);
            characterRepository.save(character);
            scheduleRetryRequest(new PortraitRequest(characterId, customPrompt), delayMs);
            log.warn(
                    "Retrying portrait generation for character {} in {}s (attempt {}/{})",
                    characterId,
                    Math.max(1L, delayMs / 1000L),
                    nextRetryCount + 1,
                    configuredMaxAttempts
            );
            return;
        }

        character.setStatus(CharacterStatus.FAILED);
        character.setNextRetryAt(null);
        characterRepository.save(character);
    }

    private void rescheduleDeferredAnalysisRetryIfNeeded(String chapterId) {
        chapterAnalysisRepository.findByChapterId(chapterId).ifPresent(analysis -> {
            if (analysis.getStatus() != ChapterAnalysisStatus.PENDING || analysis.getNextRetryAt() == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            if (!analysis.getNextRetryAt().isAfter(now)) {
                requestQueue.offer(new AnalysisRequest(chapterId));
                return;
            }
            long delayMs = Duration.between(now, analysis.getNextRetryAt()).toMillis();
            scheduleRetryRequest(new AnalysisRequest(chapterId), delayMs);
        });
    }

    private void rescheduleDeferredPortraitRetryIfNeeded(String characterId) {
        rescheduleDeferredPortraitRetryIfNeeded(characterId, null);
    }

    private void rescheduleDeferredPortraitRetryIfNeeded(String characterId, String customPrompt) {
        characterRepository.findById(characterId).ifPresent(character -> {
            if (character.getStatus() != CharacterStatus.PENDING || character.getNextRetryAt() == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            if (!character.getNextRetryAt().isAfter(now)) {
                requestQueue.offer(new PortraitRequest(characterId, customPrompt));
                return;
            }
            long delayMs = Duration.between(now, character.getNextRetryAt()).toMillis();
            scheduleRetryRequest(new PortraitRequest(characterId, customPrompt), delayMs);
        });
    }

    private long computeRetryDelayMillis(int retryCount) {
        long baseSeconds = Math.max(1, initialRetryDelaySeconds);
        long maxSeconds = Math.max(baseSeconds, maxRetryDelaySeconds);
        long delaySeconds = baseSeconds;
        for (int i = 1; i < retryCount; i++) {
            if (delaySeconds >= maxSeconds) {
                break;
            }
            delaySeconds = Math.min(maxSeconds, delaySeconds * 2);
        }
        return Math.max(1L, delaySeconds) * 1000L;
    }

    private void scheduleRetryRequest(CharacterRequest request, long delayMs) {
        long normalizedDelayMs = Math.max(0L, delayMs);
        retryScheduler.schedule(() -> {
            if (running) {
                requestQueue.offer(request);
            }
        }, normalizedDelayMs, TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory workerThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
