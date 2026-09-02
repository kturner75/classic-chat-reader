package com.classicchatreader.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.IllustrationEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.model.IllustrationStyleSuggestions;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.IllustrationRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class IllustrationService {

    private static final Logger log = LoggerFactory.getLogger(IllustrationService.class);

    private final IllustrationRepository illustrationRepository;
    private final ChapterRepository chapterRepository;
    private final BookRepository bookRepository;
    private final ParagraphRepository paragraphRepository;
    private final IllustrationPromptService promptService;
    private final IllustrationStyleAnalysisService styleAnalysisService;
    private final ComfyUIService comfyUIService;
    private final IllustrationImageGeneratorService illustrationImageGenerator;
    private final IllustrationPortraitReferences portraitReferences;
    private final AssetKeyService assetKeyService;
    private final CdnAssetService cdnAssetService;

    private final BlockingQueue<GenerationRequest> generationQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, ReentrantLock> styleAnalysisLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor();
    private ExecutorService executor;
    private volatile boolean running = true;
    private int imagineWorkerCount = ImagineInFlightLimiter.DEFAULT_MAX_IN_FLIGHT;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    @Value("${generation.imagine.max-in-flight:4}")
    private int imagineMaxInFlight;

    @Value("${illustration.generation.lease-minutes:20}")
    private int illustrationLeaseMinutes;

    @Value("${illustration.generation.worker-id:}")
    private String configuredWorkerId;

    @Value("${generation.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${generation.retry.initial-delay-seconds:30}")
    private int initialRetryDelaySeconds;

    @Value("${generation.retry.max-delay-seconds:600}")
    private int maxRetryDelaySeconds;

    private String workerId;

    // Self-injection to enable @Transactional on self-invocation
    private IllustrationService self;

    public IllustrationService(
            IllustrationRepository illustrationRepository,
            ChapterRepository chapterRepository,
            BookRepository bookRepository,
            ParagraphRepository paragraphRepository,
            IllustrationPromptService promptService,
            IllustrationStyleAnalysisService styleAnalysisService,
            ComfyUIService comfyUIService,
            IllustrationImageGeneratorService illustrationImageGenerator,
            IllustrationPortraitReferences portraitReferences,
            AssetKeyService assetKeyService,
            CdnAssetService cdnAssetService) {
        this.illustrationRepository = illustrationRepository;
        this.chapterRepository = chapterRepository;
        this.bookRepository = bookRepository;
        this.paragraphRepository = paragraphRepository;
        this.promptService = promptService;
        this.styleAnalysisService = styleAnalysisService;
        this.comfyUIService = comfyUIService;
        this.illustrationImageGenerator = illustrationImageGenerator;
        this.portraitReferences = portraitReferences;
        this.assetKeyService = assetKeyService;
        this.cdnAssetService = cdnAssetService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    public void setSelf(IllustrationService self) {
        this.self = self;
    }

    @PostConstruct
    public void init() {
        workerId = (configuredWorkerId != null && !configuredWorkerId.isBlank())
                ? configuredWorkerId
                : "illustration-" + UUID.randomUUID();
        imagineWorkerCount = Math.max(1, imagineMaxInFlight);
        executor = Executors.newFixedThreadPool(imagineWorkerCount, workerThreadFactory("illustration"));
        for (int i = 0; i < imagineWorkerCount; i++) {
            executor.submit(this::processQueue);
        }
        log.info(
                "Illustration service started with {} Imagine workers (workerId={})",
                imagineWorkerCount,
                workerId
        );
    }

    /**
     * Check if the queue processor is running (for debugging).
     */
    public boolean isQueueProcessorRunning() {
        return executor != null && !executor.isShutdown() && !executor.isTerminated();
    }

    public int getImagineWorkerCount() {
        return imagineWorkerCount;
    }

    public int getQueueDepth() {
        return generationQueue.size();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        retryScheduler.shutdownNow();
        log.info("Illustration service shutting down");
    }

    /**
     * Get the status of an illustration for a chapter.
     */
    public IllustrationStatus getStatus(String chapterId) {
        return illustrationRepository.findByChapterId(chapterId)
                .map(IllustrationEntity::getStatus)
                .orElse(null);
    }

    /**
     * Get the illustration image bytes if available.
     */
    public byte[] getIllustration(String chapterId) {
        return illustrationRepository.findByChapterId(chapterId)
                .filter(i -> i.getStatus() == IllustrationStatus.COMPLETED)
                .map(i -> comfyUIService.getImage(i.getImageFilename()))
                .orElse(null);
    }

    public Optional<String> getIllustrationFilename(String chapterId) {
        return getIllustrationAsset(chapterId).map(CdnAssetService.VersionedAsset::key);
    }

    public Optional<CdnAssetService.VersionedAsset> getIllustrationAsset(String chapterId) {
        return illustrationRepository.findByChapterId(chapterId)
                .filter(i -> i.getStatus() == IllustrationStatus.COMPLETED)
                .filter(i -> i.getImageFilename() != null && !i.getImageFilename().isBlank())
                .map(i -> new CdnAssetService.VersionedAsset(i.getImageFilename(), i.getCompletedAt()));
    }

    /**
     * Replace live illustration bytes and prompt metadata without enqueueing generation.
     * {@code source} is accepted for studio keep/restore; illustrations have no source
     * column on main, so only the prompt is persisted.
     */
    @Transactional
    public LiveAssetWriteResult saveUploadedIllustration(
            String chapterId,
            byte[] imageData,
            String source,
            String generatedPrompt,
            String promptOverride) {
        if (cacheOnly) {
            log.info("Skipping illustration upload in cache-only mode for chapter {}", chapterId);
            return LiveAssetWriteResult.CACHE_ONLY;
        }
        ChapterEntity chapter = chapterRepository.findByIdWithBook(chapterId).orElse(null);
        if (chapter == null) {
            return LiveAssetWriteResult.NOT_FOUND;
        }
        Optional<IllustrationEntity> existing = illustrationRepository.findByChapterId(chapterId);
        if (existing.isPresent() && existing.get().getStatus() == IllustrationStatus.GENERATING) {
            log.info("Rejecting illustration upload for chapter {} while generation is active", chapterId);
            return LiveAssetWriteResult.GENERATION_IN_PROGRESS;
        }
        PngImages.requirePng(imageData, "Illustration uploads must be PNG images.");
        String cacheKey = assetKeyService.buildIllustrationKey(chapter);
        String filename;
        try {
            filename = comfyUIService.saveIllustrationImage(cacheKey, imageData);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unable to save illustration: " + e.getMessage(), e);
        }

        IllustrationEntity illustration = existing.orElseGet(() -> new IllustrationEntity(chapter));
        illustration.setImageFilename(filename);
        illustration.setStatus(IllustrationStatus.COMPLETED);
        illustration.setRetryCount(0);
        illustration.setNextRetryAt(null);
        illustration.setErrorMessage(null);
        illustration.setCompletedAt(LocalDateTime.now());
        String storedPrompt = LiveAssetUploads.resolveStoredPrompt(generatedPrompt, promptOverride);
        if (storedPrompt != null) {
            illustration.setGeneratedPrompt(storedPrompt);
        }
        clearIllustrationLease(illustration);
        illustrationRepository.save(illustration);
        log.info("Saved uploaded illustration for chapter {} (source={})", chapterId, LiveAssetUploads.resolveSource(source));
        return LiveAssetWriteResult.SAVED;
    }

    /**
     * Request an illustration to be generated for a chapter.
     */
    @Transactional
    public void requestIllustration(String chapterId) {
        ChapterEntity chapter = chapterRepository.findByIdWithBook(chapterId).orElse(null);
        if (chapter == null) {
            log.warn("Cannot request illustration: chapter not found: {}", chapterId);
            return;
        }

        Optional<IllustrationEntity> existing = illustrationRepository.findByChapterId(chapterId);
        if (restoreCachedIllustrationIfPresent(chapter, existing)) {
            return;
        }

        if (cacheOnly) {
            log.info("Skipping illustration request in cache-only mode for chapter {}", chapterId);
            return;
        }

        if (existing.isPresent()) {
            IllustrationStatus status = existing.get().getStatus();
            if (status == IllustrationStatus.COMPLETED ||
                    status == IllustrationStatus.GENERATING) {
                log.debug("Illustration already {} for chapter {}", status, chapterId);
                return;
            }
            if (status == IllustrationStatus.PENDING) {
                if (existing.get().getNextRetryAt() != null
                        && existing.get().getNextRetryAt().isAfter(LocalDateTime.now())) {
                    long delayMs = Duration.between(LocalDateTime.now(), existing.get().getNextRetryAt()).toMillis();
                    scheduleRetryRequest(new IllustrationRequest(chapterId), delayMs);
                    return;
                }
                // Check if it's been stuck for more than 5 minutes
                if (existing.get().getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(5))) {
                    log.info("Re-queuing stuck PENDING illustration for chapter {}", chapterId);
                    generationQueue.offer(new IllustrationRequest(chapterId));
                } else {
                    log.debug("Illustration PENDING for chapter {} (recently requested)", chapterId);
                }
                return;
            }
            // If failed, allow retry by deleting the old record
            illustrationRepository.delete(existing.get());
            illustrationRepository.flush(); // Ensure delete is committed before insert
        }

        // Create pending record - handle race condition gracefully.
        // Flush here so uk_illustrations_chapter is raised inside this try.
        // save() alone defers the INSERT until commit, which bypasses the catch
        // and 500s the servlet (studio Regen all + prefetchNext race).
        try {
            IllustrationEntity illustration = new IllustrationEntity(chapter);
            illustrationRepository.save(illustration);
            illustrationRepository.flush();

            // Add to queue AFTER transaction commits to ensure the record is visible
            // to the background thread
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    boolean queued = generationQueue.offer(new IllustrationRequest(chapterId));
                    if (queued) {
                        log.info("Queued illustration request for chapter: {}", chapterId);
                    } else {
                        log.error("Failed to queue illustration request for chapter: {} - queue full", chapterId);
                        // Note: Cannot update status here as we're outside the transaction
                    }
                }
            });
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Another thread already created the record - this is fine
            log.debug("Illustration already being processed for chapter {} (race condition handled)", chapterId);
        } catch (Exception e) {
            log.error("Failed to create illustration request for chapter: {}", chapterId, e);
            // Try to update status to failed if record exists
            try {
                self.updateIllustrationStatus(chapterId, IllustrationStatus.FAILED, null, e.getMessage());
            } catch (Exception updateEx) {
                log.error("Failed to update status after queuing failure for chapter: {}", chapterId, updateEx);
            }
        }
    }

    /**
     * Reconcile a chapter's database state from its stable cached asset key.
     * This never queues generation, so status/read paths may safely use it in
     * cache-only deployments and after database restores.
     */
    public boolean restoreCachedIllustrationIfPresent(String chapterId) {
        ChapterEntity chapter = chapterRepository.findByIdWithBook(chapterId).orElse(null);
        if (chapter == null) {
            return false;
        }

        String cacheKey = assetKeyService.buildIllustrationKey(chapter);
        Optional<IllustrationEntity> existing = illustrationRepository.findByChapterId(chapterId);
        if (existing
                .filter(i -> i.getStatus() == IllustrationStatus.COMPLETED)
                .map(IllustrationEntity::getImageFilename)
                .filter(cacheKey::equals)
                .isPresent()) {
            return true;
        }
        if (!isCachedAssetPresent(cacheKey)) {
            return false;
        }

        // Enter through the proxy so the locking query is the first database read
        // in a fresh transaction. This avoids stale snapshots under REPEATABLE READ.
        return self.restoreCachedIllustrationRecord(chapterId, cacheKey);
    }

    @Transactional
    public boolean restoreCachedIllustrationRecord(String chapterId, String cacheKey) {
        ChapterEntity lockedChapter = chapterRepository.findByIdWithBookForUpdate(chapterId).orElse(null);
        if (lockedChapter == null) {
            return false;
        }
        restoreCachedIllustration(
                illustrationRepository.findByChapterId(chapterId)
                        .orElseGet(() -> new IllustrationEntity(lockedChapter)),
                cacheKey
        );
        return true;
    }

    private boolean restoreCachedIllustrationIfPresent(
            ChapterEntity chapter,
            Optional<IllustrationEntity> existing) {
        String cacheKey = assetKeyService.buildIllustrationKey(chapter);
        if (!comfyUIService.hasImage(cacheKey)) {
            return false;
        }
        restoreCachedIllustration(
                existing.orElseGet(() -> new IllustrationEntity(chapter)),
                cacheKey
        );
        return true;
    }

    private boolean isCachedAssetPresent(String cacheKey) {
        boolean cachedLocally = comfyUIService.hasImage(cacheKey);
        return cachedLocally
                || (cacheOnly && cdnAssetService.assetExists("illustrations", cacheKey));
    }

    /**
     * Pre-fetch the next chapter's illustration.
     */
    public void prefetchNextChapter(String currentChapterId) {
        if (cacheOnly) {
            log.info("Skipping illustration prefetch in cache-only mode for chapter {}", currentChapterId);
            return;
        }
        ChapterEntity current = chapterRepository.findById(currentChapterId).orElse(null);
        if (current == null) return;

        chapterRepository.findByBookIdAndChapterIndex(
                current.getBook().getId(),
                current.getChapterIndex() + 1
        ).ifPresent(next -> {
            log.debug("Pre-fetching illustration for next chapter: {}", next.getTitle());
            // Use self to ensure @Transactional proxy is invoked
            self.requestIllustration(next.getId());
        });
    }

    /**
     * Get or analyze illustration settings for a book.
     *
     * <p>First-time style creation is single-flight per book so parallel illustration
     * workers do not each pay for analysis. The per-book lock is acquired
     * <em>outside</em> the style transaction; check/save then runs in a
     * {@code REQUIRES_NEW} transaction so a second worker cannot re-see a
     * null-style entity from the first-level cache. The lock is held until that
     * transaction commits. Existing style is a lock-free read. Different books
     * do not share a lock.
     */
    public IllustrationSettings getOrAnalyzeBookStyle(String bookId, boolean forceReanalyze) {
        if (!forceReanalyze) {
            BookEntity book = bookRepository.findById(bookId).orElse(null);
            if (book == null) {
                return IllustrationSettings.defaults();
            }
            IllustrationSettings existing = existingStyle(book);
            if (existing != null) {
                return existing;
            }
        }
        if (cacheOnly) {
            log.info("Skipping illustration style analysis in cache-only mode for book {}", bookId);
            return IllustrationSettings.defaults();
        }

        ReentrantLock lock = styleLockFor(bookId);
        lock.lock();
        boolean holdUntilCommit = false;
        try {
            IllustrationSettings settings = self.analyzeBookStyleInNewTransaction(bookId, forceReanalyze);
            // Unit tests call the raw instance (no proxy), so REQUIRES_NEW is a no-op.
            // Hold the lock until afterCommit so a second reader cannot findById
            // the pre-commit row. In production, self is the Spring proxy and the
            // inner transaction has already committed when this returns.
            if (self == this && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(unlockStyleAfterCommit(lock));
                holdUntilCommit = true;
            }
            return settings;
        } finally {
            if (!holdUntilCommit) {
                unlockStyleIfHeld(lock);
            }
        }
    }

    /**
     * Fresh persistence context for the locked style check/save. Invoked via
     * {@code self} so {@code REQUIRES_NEW} applies. Callers should use
     * {@link #getOrAnalyzeBookStyle(String, boolean)}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IllustrationSettings analyzeBookStyleInNewTransaction(String bookId, boolean forceReanalyze) {
        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return IllustrationSettings.defaults();
        }
        if (!forceReanalyze) {
            IllustrationSettings existing = existingStyle(book);
            if (existing != null) {
                return existing;
            }
        }
        if (cacheOnly) {
            log.info("Skipping illustration style analysis in cache-only mode for book {}", bookId);
            return IllustrationSettings.defaults();
        }

        String openingText = getBookOpeningText(book);
        IllustrationSettings settings = styleAnalysisService.analyzeBookForStyle(
                book.getTitle(),
                book.getAuthor(),
                openingText
        );

        book.setIllustrationStyle(IllustrationSettings.clip(settings.style(), IllustrationSettings.STYLE_MAX));
        book.setIllustrationPromptPrefix(
                IllustrationSettings.clip(settings.promptPrefix(), IllustrationSettings.PREFIX_MAX));
        book.setIllustrationSetting(clipOrNull(settings.setting(), IllustrationSettings.SETTING_MAX));
        book.setIllustrationStyleReasoning(clipOrNull(settings.reasoning(), IllustrationSettings.REASONING_MAX));
        book.setIllustrationCoverSubject(clipOrNull(settings.coverSubject(), IllustrationSettings.COVER_SUBJECT_MAX));
        book.setIllustrationCoverFocus(clipOrNull(settings.coverFocus(), IllustrationSettings.COVER_FOCUS_MAX));
        bookRepository.save(book);

        log.info("Analyzed illustration style for '{}': {} - {}",
                book.getTitle(), settings.style(), settings.reasoning());
        return settings;
    }

    /** Operator override of the book-wide Imagine style (portraits, covers, chapter plates). */
    @Transactional
    public IllustrationSettings updateBookStyle(String bookId, IllustrationSettings incoming) {
        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return null;
        }
        if (incoming != null) {
            if (incoming.style() != null && !incoming.style().isBlank()) {
                book.setIllustrationStyle(IllustrationSettings.clip(incoming.style(), IllustrationSettings.STYLE_MAX));
            } else if (book.getIllustrationStyle() == null) {
                book.setIllustrationStyle("watercolor");
            }
            if (incoming.promptPrefix() != null) {
                book.setIllustrationPromptPrefix(
                        IllustrationSettings.clip(incoming.promptPrefix(), IllustrationSettings.PREFIX_MAX));
            }
            if (incoming.setting() != null) {
                book.setIllustrationSetting(clipOrNull(incoming.setting(), IllustrationSettings.SETTING_MAX));
            }
            if (incoming.reasoning() != null) {
                book.setIllustrationStyleReasoning(clipOrNull(incoming.reasoning(), IllustrationSettings.REASONING_MAX));
            }
            if (incoming.coverSubject() != null) {
                book.setIllustrationCoverSubject(
                        clipOrNull(incoming.coverSubject(), IllustrationSettings.COVER_SUBJECT_MAX));
            }
            if (incoming.coverFocus() != null) {
                book.setIllustrationCoverFocus(clipOrNull(incoming.coverFocus(), IllustrationSettings.COVER_FOCUS_MAX));
            }
        }
        if (book.getIllustrationStyle() == null) {
            book.setIllustrationStyle("watercolor");
        }
        bookRepository.save(book);
        log.info("Updated illustration style for '{}': {}", book.getTitle(), book.getIllustrationStyle());
        return existingStyle(book);
    }

    /** Operator choices: does not persist. Pick one and PUT /settings/{bookId}. */
    public IllustrationStyleSuggestions suggestBookStyles(String bookId, int limit) {
        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return null;
        }
        if (cacheOnly) {
            log.info("Skipping illustration style suggestions in cache-only mode for book {}", bookId);
            return IllustrationStyleSuggestions.empty();
        }
        return styleAnalysisService.suggestStylesForBook(
                book.getTitle(),
                book.getAuthor(),
                getBookOpeningText(book),
                limit
        );
    }

    private static IllustrationSettings existingStyle(BookEntity book) {
        if (book.getIllustrationStyle() == null) {
            return null;
        }
        return new IllustrationSettings(
                book.getIllustrationStyle(),
                book.getIllustrationPromptPrefix(),
                book.getIllustrationSetting(),
                book.getIllustrationStyleReasoning(),
                book.getIllustrationCoverSubject(),
                book.getIllustrationCoverFocus()
        );
    }

    private ReentrantLock styleLockFor(String bookId) {
        return styleAnalysisLocks.computeIfAbsent(bookId, id -> new ReentrantLock());
    }

    private static TransactionSynchronization unlockStyleAfterCommit(ReentrantLock lock) {
        return new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                unlockStyleIfHeld(lock);
            }

            @Override
            public void afterCompletion(int status) {
                unlockStyleIfHeld(lock);
            }
        };
    }

    private static void unlockStyleIfHeld(ReentrantLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String clipOrNull(String value, int max) {
        String clipped = IllustrationSettings.clip(value, max);
        return clipped == null || clipped.isEmpty() ? null : clipped;
    }

    /**
     * Background queue processor.
     */
    private void processQueue() {
        log.info("Illustration queue processor thread started");
        int processedCount = 0;
        while (running) {
            try {
                log.debug("Waiting for illustration request in queue...");
                GenerationRequest request = generationQueue.take();
                if (cacheOnly) {
                    log.info("Skipping queued illustration request in cache-only mode for chapter {}", request.chapterId());
                    continue;
                }
                processedCount++;
                log.info("Processing illustration request #{} for chapter: {}", processedCount, request.chapterId());
                boolean rewritePrompt = request instanceof RegenerateRequest;
                String customPrompt = rewritePrompt ? blankToNull(((RegenerateRequest) request).customPrompt()) : null;
                generateIllustration(request.chapterId(), customPrompt, rewritePrompt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Illustration queue processor thread interrupted");
                break;
            } catch (Exception e) {
                log.error("Error processing illustration queue", e);
            }
        }
        log.info("Illustration queue processor thread stopped after processing {} requests", processedCount);
    }

    /**
     * Generate illustration for a chapter.
     * @param customPrompt If provided, skip LLM prompt generation and use this prompt directly
     * @param rewritePrompt Operator asked to regenerate: do not restore a cached plate
     */
    private void generateIllustration(String chapterId, String customPrompt, boolean rewritePrompt) {
        if (!tryClaimGenerationLease(chapterId)) {
            log.debug("Skipping illustration generation for chapter {} because lease claim failed", chapterId);
            rescheduleDeferredRetryIfNeeded(chapterId, customPrompt);
            return;
        }
        log.info("Starting illustration generation for chapter: {}{}", chapterId,
                customPrompt != null ? " (with custom prompt)" : "");

        // Fetch chapter with book eagerly to avoid lazy loading issues in background thread
        ChapterEntity chapter = chapterRepository.findByIdWithBook(chapterId).orElse(null);
        if (chapter == null) {
            log.error("Chapter not found: {}", chapterId);
            self.handleGenerationFailure(chapterId, "Chapter not found", customPrompt, false);
            return;
        }
        BookEntity book = chapter.getBook();
        String cacheKey = assetKeyService.buildIllustrationKey(chapter);
        if (customPrompt == null && !rewritePrompt && comfyUIService.hasImage(cacheKey)) {
            illustrationRepository.findByChapterId(chapterId)
                    .ifPresent(illustration -> restoreCachedIllustration(illustration, cacheKey));
            return;
        }
        if (cacheOnly) {
            log.info("Skipping illustration generation in cache-only mode for chapter {}", chapterId);
            self.updateIllustrationStatus(chapterId, IllustrationStatus.PENDING, null, null);
            return;
        }

        try {
            String chapterContent = getChapterText(chapterId);
            if (IllustrationPromptService.isSilhouetteEraPrompt(customPrompt)) {
                log.info("Dropping silhouette-era prompt for chapter {}; writing a new one", chapterId);
                customPrompt = null;
            }
            String imagePrompt;
            List<IllustrationPortraitReferences.PortraitRef> portraitRefs;

            if (customPrompt != null) {
                imagePrompt = customPrompt;
                portraitRefs = portraitReferences.select(book.getId(), chapter, customPrompt);
            } else {
                var cast = portraitReferences.castForChapter(book.getId(), chapter, chapterContent);
                var castNames = IllustrationPortraitReferences.namesOf(cast);
                IllustrationSettings styleSettings = self.getOrAnalyzeBookStyle(book.getId(), false);
                imagePrompt = promptService.generatePromptForChapter(
                        book.getTitle(),
                        book.getAuthor(),
                        chapter.getTitle(),
                        chapterContent,
                        styleSettings,
                        castNames
                );
                imagePrompt = IllustrationPortraitReferences.ensureCastNamed(imagePrompt, castNames);
                portraitRefs = portraitReferences.load(cast);
            }
            imagePrompt = IllustrationPortraitReferences.appendLikeness(imagePrompt, portraitRefs);
            imagePrompt = IllustrationPromptService.ensureNarrativePlate(imagePrompt);
            if (customPrompt == null) {
                self.updateIllustrationPrompt(chapterId, imagePrompt);
            }

            String outputPrefix = "illustration_" + chapterId;
            String filename = illustrationImageGenerator.generateIllustration(
                    imagePrompt, outputPrefix, cacheKey, portraitRefs);
            self.updateIllustrationStatus(chapterId, IllustrationStatus.COMPLETED, filename, null);
            log.info("Illustration completed for chapter: {} via {}",
                    chapterId, illustrationImageGenerator.getProviderName());

        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                log.info("Illustration generation interrupted for chapter {}", chapterId);
                self.updateIllustrationStatus(chapterId, IllustrationStatus.PENDING, null, null);
                return;
            }
            log.error("Failed to generate illustration for chapter: {}", chapterId, e);
            self.handleGenerationFailure(chapterId, e.getMessage(), customPrompt, true);
        }
    }

    /**
     * Update illustration status in a fresh transaction.
     * This ensures the update is properly committed even when called from a background thread.
     */
    @Transactional
    public void updateIllustrationStatus(String chapterId, IllustrationStatus status, String filename, String errorMessage) {
        IllustrationEntity illustration = illustrationRepository.findByChapterId(chapterId).orElse(null);
        if (illustration == null) {
            log.warn("Cannot update status: illustration not found for chapter {}", chapterId);
            return;
        }
        illustration.setStatus(status);
        if (filename != null) {
            illustration.setImageFilename(filename);
        }
        if (errorMessage != null) {
            illustration.setErrorMessage(errorMessage);
        }
        if (status == IllustrationStatus.COMPLETED) {
            illustration.setCompletedAt(LocalDateTime.now());
            illustration.setErrorMessage(null);
            illustration.setRetryCount(0);
        }
        if (status != IllustrationStatus.PENDING) {
            illustration.setNextRetryAt(null);
        }
        if (status != IllustrationStatus.GENERATING) {
            clearIllustrationLease(illustration);
        }
        illustrationRepository.save(illustration);
        log.debug("Updated illustration status for chapter {}: {}", chapterId, status);
    }

    private void restoreCachedIllustration(IllustrationEntity illustration, String cacheKey) {
        illustration.setStatus(IllustrationStatus.COMPLETED);
        illustration.setImageFilename(cacheKey);
        illustration.setErrorMessage(null);
        illustration.setRetryCount(0);
        illustration.setNextRetryAt(null);
        illustration.setCompletedAt(LocalDateTime.now());
        clearIllustrationLease(illustration);
        illustrationRepository.save(illustration);
        log.info("Restored cached illustration for chapter {} from {}",
                illustration.getChapter().getId(), cacheKey);
    }

    /**
     * Update illustration prompt in a fresh transaction.
     */
    @Transactional
    public void updateIllustrationPrompt(String chapterId, String prompt) {
        IllustrationEntity illustration = illustrationRepository.findByChapterId(chapterId).orElse(null);
        if (illustration == null) {
            return;
        }
        illustration.setGeneratedPrompt(prompt);
        illustrationRepository.save(illustration);
    }

    private String getBookOpeningText(BookEntity book) {
        List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(book.getId());
        if (chapters.isEmpty()) return "";

        // Get first chapter's paragraphs
        ChapterEntity firstChapter = chapters.get(0);
        List<ParagraphEntity> paragraphs = paragraphRepository
                .findByChapterIdOrderByParagraphIndex(firstChapter.getId());

        return paragraphs.stream()
                .limit(10)
                .map(ParagraphEntity::getContent)
                .map(this::stripHtml)
                .collect(Collectors.joining("\n\n"));
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

    /**
     * Get the prompt used for a chapter's illustration.
     */
    public String getPrompt(String chapterId) {
        return illustrationRepository.findByChapterId(chapterId)
                .map(IllustrationEntity::getGeneratedPrompt)
                .orElse(null);
    }

    /**
     * Regenerate illustration with a custom prompt.
     */
    @Transactional
    public void regenerateWithPrompt(String chapterId, String customPrompt) {
        if (cacheOnly) {
            log.info("Skipping illustration regeneration in cache-only mode for chapter {}", chapterId);
            return;
        }
        Optional<IllustrationEntity> existing = illustrationRepository.findByChapterId(chapterId);

        if (existing.isEmpty()) {
            log.warn("Cannot regenerate: no illustration record for chapter {}", chapterId);
            return;
        }

        IllustrationEntity illustration = existing.get();
        String prompt = blankToNull(customPrompt);

        // Reset to pending. Blank prompt means write a new LLM prompt (skip cached plate).
        illustration.setStatus(IllustrationStatus.PENDING);
        illustration.setGeneratedPrompt(prompt);
        illustration.setErrorMessage(null);
        illustration.setImageFilename(null);
        illustration.setCompletedAt(null);
        illustration.setRetryCount(0);
        illustration.setNextRetryAt(null);
        clearIllustrationLease(illustration);
        illustrationRepository.save(illustration);

        enqueueAfterCommit(new RegenerateRequest(chapterId, prompt));
        log.info("Queued illustration regeneration for chapter: {}{}", chapterId,
                prompt == null ? " (new prompt)" : " (custom prompt)");
    }

    /**
     * Retry stuck PENDING illustrations by re-queuing them.
     * This can be called manually to fix stuck illustrations.
     */
    @Transactional
    public void retryStuckPendingIllustrations() {
        if (cacheOnly) {
            log.info("Skipping illustration retry in cache-only mode");
            return;
        }
        List<IllustrationEntity> stuckIllustrations = illustrationRepository.findByStatus(IllustrationStatus.PENDING);
        log.info("Found {} stuck PENDING illustrations", stuckIllustrations.size());

        for (IllustrationEntity illustration : stuckIllustrations) {
            String chapterId = illustration.getChapter().getId();
            // Check if it's been stuck for more than 5 minutes
            if (illustration.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(5))) {
                log.info("Re-queuing stuck PENDING illustration for chapter: {}", chapterId);
                generationQueue.offer(new IllustrationRequest(chapterId));
            }
        }
    }

    /**
     * Force re-queue all pending illustrations for a specific book.
     * Used by pre-generation to ensure all items get processed.
     */
    @Transactional(readOnly = true)
    public int forceQueuePendingForBook(String bookId) {
        if (cacheOnly) {
            log.info("Skipping illustration re-queue in cache-only mode for book {}", bookId);
            return 0;
        }
        List<IllustrationEntity> pendingIllustrations = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.PENDING);
        int queued = 0;
        for (IllustrationEntity illustration : pendingIllustrations) {
            String chapterId = illustration.getChapter().getId();
            if (generationQueue.offer(new IllustrationRequest(chapterId))) {
                queued++;
            }
        }
        log.info("Force-queued {} pending illustrations for book {}", queued, bookId);
        return queued;
    }

    /**
     * Reset stuck GENERATING illustrations back to PENDING and re-queue them.
     * Used when generation appears stalled.
     */
    @Transactional
    public int resetAndRequeueStuckForBook(String bookId) {
        List<IllustrationEntity> stuckGenerating = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.GENERATING);
        List<IllustrationEntity> stuckPending = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.PENDING);
        List<IllustrationEntity> failed = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.FAILED);

        int restored = 0;
        for (IllustrationEntity illustration : java.util.stream.Stream
                .of(stuckGenerating, stuckPending, failed)
                .flatMap(List::stream)
                .toList()) {
            String cacheKey = assetKeyService.buildIllustrationKey(illustration.getChapter());
            if (comfyUIService.hasImage(cacheKey)) {
                restoreCachedIllustration(illustration, cacheKey);
                restored++;
            }
        }

        if (cacheOnly) {
            log.info("Restored {} cached illustrations and skipped illustration reset/re-queue in cache-only mode for book {}",
                    restored, bookId);
            return restored;
        }

        int reset = 0;
        for (IllustrationEntity illustration : stuckGenerating) {
            if (illustration.getStatus() == IllustrationStatus.COMPLETED) {
                continue;
            }
            illustration.setStatus(IllustrationStatus.PENDING);
            illustration.setRetryCount(0);
            illustration.setNextRetryAt(null);
            clearIllustrationLease(illustration);
            illustrationRepository.save(illustration);
            reset++;
        }

        // Re-queue all pending (including just-reset ones)
        int queued = 0;
        for (IllustrationEntity illustration : stuckGenerating) {
            if (illustration.getStatus() != IllustrationStatus.COMPLETED
                    && generationQueue.offer(new IllustrationRequest(illustration.getChapter().getId()))) {
                queued++;
            }
        }
        for (IllustrationEntity illustration : stuckPending) {
            if (illustration.getStatus() != IllustrationStatus.COMPLETED
                    && generationQueue.offer(new IllustrationRequest(illustration.getChapter().getId()))) {
                queued++;
            }
        }

        log.info("Restored {} cached illustrations, reset {} stuck GENERATING illustrations, and queued {} total for book {}",
                restored, reset, queued, bookId);
        return restored + reset + (int) stuckPending.stream()
                .filter(illustration -> illustration.getStatus() != IllustrationStatus.COMPLETED)
                .count();
    }

    private sealed interface GenerationRequest permits IllustrationRequest, RegenerateRequest {
        String chapterId();
    }
    private record IllustrationRequest(String chapterId) implements GenerationRequest {}
    private record RegenerateRequest(String chapterId, String customPrompt) implements GenerationRequest {}

    private boolean tryClaimGenerationLease(String chapterId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiresAt = now.plusMinutes(Math.max(1, illustrationLeaseMinutes));
        int claimed = illustrationRepository.claimGenerationLease(
                chapterId,
                now,
                leaseExpiresAt,
                workerId,
                IllustrationStatus.PENDING,
                IllustrationStatus.GENERATING
        );
        return claimed > 0;
    }

    private void clearIllustrationLease(IllustrationEntity illustration) {
        illustration.setLeaseOwner(null);
        illustration.setLeaseExpiresAt(null);
    }

    @Transactional
    public void handleGenerationFailure(
            String chapterId,
            String errorMessage,
            String customPrompt,
            boolean retryable) {
        IllustrationEntity illustration = illustrationRepository.findByChapterId(chapterId).orElse(null);
        if (illustration == null) {
            log.warn("Cannot record illustration failure: illustration not found for chapter {}", chapterId);
            return;
        }

        int nextRetryCount = Math.max(0, illustration.getRetryCount()) + 1;
        illustration.setErrorMessage(errorMessage);
        illustration.setRetryCount(nextRetryCount);
        clearIllustrationLease(illustration);

        int configuredMaxAttempts = Math.max(1, maxRetryAttempts);
        if (retryable && nextRetryCount < configuredMaxAttempts) {
            long delayMs = computeRetryDelayMillis(nextRetryCount);
            LocalDateTime nextRetryAt = LocalDateTime.now().plus(Duration.ofMillis(delayMs));
            illustration.setStatus(IllustrationStatus.PENDING);
            illustration.setNextRetryAt(nextRetryAt);
            illustrationRepository.save(illustration);
            scheduleRetryRequest(buildRetryRequest(chapterId, customPrompt), delayMs);
            log.warn(
                    "Retrying illustration generation for chapter {} in {}s (attempt {}/{})",
                    chapterId,
                    Math.max(1L, delayMs / 1000L),
                    nextRetryCount + 1,
                    configuredMaxAttempts
            );
            return;
        }

        illustration.setStatus(IllustrationStatus.FAILED);
        illustration.setNextRetryAt(null);
        illustrationRepository.save(illustration);
    }

    private void rescheduleDeferredRetryIfNeeded(String chapterId, String customPrompt) {
        illustrationRepository.findByChapterId(chapterId).ifPresent(illustration -> {
            if (illustration.getStatus() != IllustrationStatus.PENDING || illustration.getNextRetryAt() == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            if (!illustration.getNextRetryAt().isAfter(now)) {
                generationQueue.offer(buildRetryRequest(chapterId, customPrompt));
                return;
            }
            long delayMs = Duration.between(now, illustration.getNextRetryAt()).toMillis();
            scheduleRetryRequest(buildRetryRequest(chapterId, customPrompt), delayMs);
        });
    }

    private GenerationRequest buildRetryRequest(String chapterId, String customPrompt) {
        if (customPrompt != null && !customPrompt.isBlank()) {
            return new RegenerateRequest(chapterId, customPrompt);
        }
        return new IllustrationRequest(chapterId);
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

    private void enqueueAfterCommit(GenerationRequest request) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    boolean queued = generationQueue.offer(request);
                    if (!queued) {
                        log.error("Failed to queue illustration {} for chapter {} - queue full",
                                request.getClass().getSimpleName(), request.chapterId());
                    }
                }
            });
            return;
        }
        generationQueue.offer(request);
    }

    private void scheduleRetryRequest(GenerationRequest request, long delayMs) {
        long normalizedDelayMs = Math.max(0L, delayMs);
        retryScheduler.schedule(() -> {
            if (running) {
                generationQueue.offer(request);
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
