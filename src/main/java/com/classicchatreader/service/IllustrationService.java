package com.classicchatreader.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.IllustrationEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.IllustrationRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final AssetKeyService assetKeyService;

    private final BlockingQueue<GenerationRequest> generationQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = true;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

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
            AssetKeyService assetKeyService) {
        this.illustrationRepository = illustrationRepository;
        this.chapterRepository = chapterRepository;
        this.bookRepository = bookRepository;
        this.paragraphRepository = paragraphRepository;
        this.promptService = promptService;
        this.styleAnalysisService = styleAnalysisService;
        this.comfyUIService = comfyUIService;
        this.assetKeyService = assetKeyService;
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
        executor.submit(this::processQueue);
        log.info("Illustration service started with background queue processor (workerId={})", workerId);
    }

    /**
     * Check if the queue processor is running (for debugging).
     */
    public boolean isQueueProcessorRunning() {
        return !executor.isShutdown() && !executor.isTerminated();
    }

    public int getQueueDepth() {
        return generationQueue.size();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
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
        return illustrationRepository.findByChapterId(chapterId)
                .filter(i -> i.getStatus() == IllustrationStatus.COMPLETED)
                .map(IllustrationEntity::getImageFilename);
    }

    /**
     * Request an illustration to be generated for a chapter.
     */
    @Transactional
    public void requestIllustration(String chapterId) {
        if (cacheOnly) {
            log.info("Skipping illustration request in cache-only mode for chapter {}", chapterId);
            return;
        }
        Optional<IllustrationEntity> existing = illustrationRepository.findByChapterId(chapterId);

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

        ChapterEntity chapter = chapterRepository.findById(chapterId).orElse(null);
        if (chapter == null) {
            log.warn("Cannot request illustration: chapter not found: {}", chapterId);
            return;
        }

        // Create pending record - handle race condition gracefully
        try {
            IllustrationEntity illustration = new IllustrationEntity(chapter);
            illustrationRepository.save(illustration);

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
     */
    @Transactional
    public IllustrationSettings getOrAnalyzeBookStyle(String bookId, boolean forceReanalyze) {
        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return IllustrationSettings.defaults();
        }

        // Check if already analyzed
        if (!forceReanalyze && book.getIllustrationStyle() != null) {
            return new IllustrationSettings(
                    book.getIllustrationStyle(),
                    book.getIllustrationPromptPrefix(),
                    book.getIllustrationSetting(),
                    book.getIllustrationStyleReasoning()
            );
        }
        if (cacheOnly) {
            log.info("Skipping illustration style analysis in cache-only mode for book {}", bookId);
            return IllustrationSettings.defaults();
        }

        // Get opening text for analysis
        String openingText = getBookOpeningText(book);

        // Analyze with LLM
        IllustrationSettings settings = styleAnalysisService.analyzeBookForStyle(
                book.getTitle(),
                book.getAuthor(),
                openingText
        );

        // Save to book entity
        book.setIllustrationStyle(settings.style());
        book.setIllustrationPromptPrefix(settings.promptPrefix());
        book.setIllustrationSetting(settings.setting());
        book.setIllustrationStyleReasoning(settings.reasoning());
        bookRepository.save(book);

        log.info("Analyzed illustration style for '{}': {} - {}",
                book.getTitle(), settings.style(), settings.reasoning());

        return settings;
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
                String customPrompt = (request instanceof RegenerateRequest r) ? r.customPrompt() : null;
                generateIllustration(request.chapterId(), customPrompt);
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
     */
    private void generateIllustration(String chapterId, String customPrompt) {
        if (cacheOnly) {
            log.info("Skipping illustration generation in cache-only mode for chapter {}", chapterId);
            return;
        }
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

        try {
            String imagePrompt;

            if (customPrompt != null) {
                // Use the custom prompt directly
                imagePrompt = customPrompt;
            } else {
                // Get or analyze book style (use self to ensure @Transactional proxy is invoked)
                IllustrationSettings styleSettings = self.getOrAnalyzeBookStyle(book.getId(), false);

                // Get chapter content
                String chapterContent = getChapterText(chapterId);

                // Generate prompt with LLM
                imagePrompt = promptService.generatePromptForChapter(
                        book.getTitle(),
                        book.getAuthor(),
                        chapter.getTitle(),
                        chapterContent,
                        styleSettings
                );

                self.updateIllustrationPrompt(chapterId, imagePrompt);
            }

            // Submit to ComfyUI
            String outputPrefix = "illustration_" + chapterId;
            String cacheKey = assetKeyService.buildIllustrationKey(chapter);
            String promptId = comfyUIService.submitWorkflow(imagePrompt, outputPrefix, cacheKey);

            // Poll for completion
            ComfyUIService.IllustrationResult result = comfyUIService.pollForCompletion(promptId);

            if (result.success()) {
                self.updateIllustrationStatus(chapterId, IllustrationStatus.COMPLETED, cacheKey, null);
                log.info("Illustration completed for chapter: {}", chapterId);
            } else {
                self.handleGenerationFailure(chapterId, result.errorMessage(), customPrompt, true);
                log.warn("Illustration generation failed for chapter {}: {}", chapterId, result.errorMessage());
            }

        } catch (Exception e) {
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

        // Reset to pending with the custom prompt
        illustration.setStatus(IllustrationStatus.PENDING);
        illustration.setGeneratedPrompt(customPrompt);
        illustration.setErrorMessage(null);
        illustration.setImageFilename(null);
        illustration.setCompletedAt(null);
        illustration.setRetryCount(0);
        illustration.setNextRetryAt(null);
        clearIllustrationLease(illustration);
        illustrationRepository.save(illustration);

        // Add to queue for regeneration
        generationQueue.offer(new RegenerateRequest(chapterId, customPrompt));
        log.info("Queued illustration regeneration for chapter: {}", chapterId);
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
        if (cacheOnly) {
            log.info("Skipping illustration reset/re-queue in cache-only mode for book {}", bookId);
            return 0;
        }
        List<IllustrationEntity> stuckGenerating = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.GENERATING);
        List<IllustrationEntity> stuckPending = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.PENDING);

        int reset = 0;
        for (IllustrationEntity illustration : stuckGenerating) {
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
            if (generationQueue.offer(new IllustrationRequest(illustration.getChapter().getId()))) {
                queued++;
            }
        }
        for (IllustrationEntity illustration : stuckPending) {
            if (generationQueue.offer(new IllustrationRequest(illustration.getChapter().getId()))) {
                queued++;
            }
        }

        log.info("Reset {} stuck GENERATING illustrations and queued {} total for book {}", reset, queued, bookId);
        return reset + stuckPending.size();
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

    private void scheduleRetryRequest(GenerationRequest request, long delayMs) {
        long normalizedDelayMs = Math.max(0L, delayMs);
        retryScheduler.schedule(() -> {
            if (running) {
                generationQueue.offer(request);
            }
        }, normalizedDelayMs, TimeUnit.MILLISECONDS);
    }
}
