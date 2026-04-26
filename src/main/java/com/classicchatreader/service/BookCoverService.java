package com.classicchatreader.service;

import com.classicchatreader.entity.BookCoverEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.repository.BookCoverRepository;
import com.classicchatreader.repository.BookRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class BookCoverService {

    private static final Logger log = LoggerFactory.getLogger(BookCoverService.class);

    private final BookCoverRepository bookCoverRepository;
    private final BookRepository bookRepository;
    private final IllustrationService illustrationService;
    private final ComfyUIService comfyUIService;
    private final BookCoverImageGeneratorService bookCoverImageGeneratorService;
    private final AssetKeyService assetKeyService;

    private final BlockingQueue<CoverRequest> generationQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = true;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    @Value("${book-cover.generation.lease-minutes:20}")
    private int coverLeaseMinutes;

    @Value("${generation.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${generation.retry.initial-delay-seconds:30}")
    private int initialRetryDelaySeconds;

    @Value("${generation.retry.max-delay-seconds:600}")
    private int maxRetryDelaySeconds;

    private String workerId;
    private BookCoverService self;

    public BookCoverService(
            BookCoverRepository bookCoverRepository,
            BookRepository bookRepository,
            IllustrationService illustrationService,
            ComfyUIService comfyUIService,
            BookCoverImageGeneratorService bookCoverImageGeneratorService,
            AssetKeyService assetKeyService) {
        this.bookCoverRepository = bookCoverRepository;
        this.bookRepository = bookRepository;
        this.illustrationService = illustrationService;
        this.comfyUIService = comfyUIService;
        this.bookCoverImageGeneratorService = bookCoverImageGeneratorService;
        this.assetKeyService = assetKeyService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    public void setSelf(BookCoverService self) {
        this.self = self;
    }

    @PostConstruct
    public void init() {
        workerId = "book-cover-" + UUID.randomUUID();
        executor.submit(this::processQueue);
        log.info("Book cover service started with background queue processor (workerId={})", workerId);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
        retryScheduler.shutdownNow();
        log.info("Book cover service shutting down");
    }

    public int getQueueDepth() {
        return generationQueue.size();
    }

    public IllustrationStatus getStatus(String bookId) {
        return bookCoverRepository.findByBookId(bookId)
                .map(BookCoverEntity::getStatus)
                .orElse(null);
    }

    public Optional<CoverStatus> getCoverStatus(String bookId) {
        return bookCoverRepository.findByBookId(bookId)
                .map(cover -> new CoverStatus(
                        bookId,
                        cover.getStatus(),
                        cover.getImageFilename(),
                        cover.getGeneratedPrompt(),
                        cover.getPromptOverride(),
                        cover.getCoverSource(),
                        cover.getErrorMessage(),
                        cover.getCompletedAt()
                ));
    }

    public Optional<String> getCoverFilename(String bookId) {
        return bookCoverRepository.findByBookId(bookId)
                .filter(cover -> cover.getImageFilename() != null && !cover.getImageFilename().isBlank())
                .map(BookCoverEntity::getImageFilename);
    }

    public byte[] getCover(String bookId) {
        return getCoverFilename(bookId)
                .map(comfyUIService::getBookCoverImage)
                .orElse(null);
    }

    @Transactional
    public void requestCover(String bookId) {
        if (cacheOnly) {
            log.info("Skipping book cover request in cache-only mode for book {}", bookId);
            return;
        }

        Optional<BookCoverEntity> existing = bookCoverRepository.findByBookId(bookId);
        if (existing.isPresent()) {
            IllustrationStatus status = existing.get().getStatus();
            if (status == IllustrationStatus.COMPLETED || status == IllustrationStatus.GENERATING) {
                log.debug("Book cover already {} for book {}", status, bookId);
                return;
            }
            if (status == IllustrationStatus.PENDING) {
                if (existing.get().getNextRetryAt() != null
                        && existing.get().getNextRetryAt().isAfter(LocalDateTime.now())) {
                    long delayMs = Duration.between(LocalDateTime.now(), existing.get().getNextRetryAt()).toMillis();
                    scheduleRetryRequest(new CoverRequest(bookId), delayMs);
                    return;
                }
                generationQueue.offer(new CoverRequest(bookId));
                return;
            }
            bookCoverRepository.delete(existing.get());
            bookCoverRepository.flush();
        }

        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            log.warn("Cannot request book cover: book not found {}", bookId);
            return;
        }

        try {
            bookCoverRepository.save(new BookCoverEntity(book));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    generationQueue.offer(new CoverRequest(bookId));
                    log.info("Queued book cover request for book: {}", bookId);
                }
            });
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.debug("Book cover already being processed for book {} (race condition handled)", bookId);
        }
    }

    @Transactional
    public boolean retryCover(String bookId, String promptOverride) {
        return retryCover(bookId, promptOverride, false);
    }

    @Transactional
    public boolean retryCoverWithFreshGeneratedPrompt(String bookId) {
        return retryCover(bookId, null, true);
    }

    private boolean retryCover(String bookId, String promptOverride, boolean clearGeneratedPrompt) {
        if (cacheOnly) {
            log.info("Skipping book cover retry in cache-only mode for book {}", bookId);
            return false;
        }

        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return false;
        }

        BookCoverEntity cover = bookCoverRepository.findByBookId(bookId).orElseGet(() -> new BookCoverEntity(book));
        cover.setStatus(IllustrationStatus.PENDING);
        cover.setRetryCount(0);
        cover.setNextRetryAt(null);
        cover.setErrorMessage(null);
        if (cover.getImageFilename() == null || cover.getImageFilename().isBlank()) {
            cover.setCompletedAt(null);
        }
        clearCoverLease(cover);
        String normalizedPrompt = normalizePromptOverride(promptOverride);
        if (normalizedPrompt != null) {
            cover.setPromptOverride(normalizedPrompt);
            cover.setGeneratedPrompt(normalizedPrompt);
            cover.setCoverSource("prompt_override");
        } else {
            cover.setPromptOverride(null);
            if (clearGeneratedPrompt) {
                cover.setGeneratedPrompt(null);
            }
            cover.setCoverSource(null);
        }
        bookCoverRepository.save(cover);
        enqueueAfterCommit(bookId);
        return true;
    }

    @Transactional
    public boolean saveManualCover(String bookId, byte[] imageData) {
        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return false;
        }
        validatePngImage(imageData);
        String cacheKey = assetKeyService.buildBookCoverKey(book);
        String filename;
        try {
            filename = comfyUIService.saveBookCoverImage(cacheKey, imageData);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unable to save manual book cover: " + e.getMessage(), e);
        }

        BookCoverEntity cover = bookCoverRepository.findByBookId(bookId).orElseGet(() -> new BookCoverEntity(book));
        cover.setImageFilename(filename);
        cover.setStatus(IllustrationStatus.COMPLETED);
        cover.setRetryCount(0);
        cover.setNextRetryAt(null);
        cover.setErrorMessage(null);
        cover.setCompletedAt(LocalDateTime.now());
        cover.setCoverSource("manual_upload");
        clearCoverLease(cover);
        bookCoverRepository.save(cover);
        return true;
    }

    public CoverGenerationResult generateCoverAndWait(String bookId, Duration timeout) {
        if (cacheOnly) {
            return CoverGenerationResult.failure("Book cover generation is unavailable in cache-only mode.");
        }
        self.requestCover(bookId);
        long deadline = System.currentTimeMillis() + Math.max(1L, timeout.toMillis());
        while (System.currentTimeMillis() < deadline) {
            Optional<BookCoverEntity> cover = bookCoverRepository.findByBookId(bookId);
            if (cover.isPresent()) {
                IllustrationStatus status = cover.get().getStatus();
                if (status == IllustrationStatus.COMPLETED) {
                    return CoverGenerationResult.success(false);
                }
                if (status == IllustrationStatus.FAILED) {
                    return CoverGenerationResult.failure(cover.get().getErrorMessage());
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CoverGenerationResult.failure("Interrupted while waiting for book cover generation.");
            }
        }
        return CoverGenerationResult.failure("Timed out waiting for book cover generation.");
    }

    @Transactional(readOnly = true)
    public int forceQueuePendingForBook(String bookId) {
        if (cacheOnly) {
            return 0;
        }
        Optional<BookCoverEntity> pending = bookCoverRepository.findByBookId(bookId)
                .filter(cover -> cover.getStatus() == IllustrationStatus.PENDING);
        if (pending.isPresent() && generationQueue.offer(new CoverRequest(bookId))) {
            return 1;
        }
        return 0;
    }

    @Transactional
    public int resetAndRequeueStuckForBook(String bookId) {
        if (cacheOnly) {
            return 0;
        }
        Optional<BookCoverEntity> coverOpt = bookCoverRepository.findByBookId(bookId)
                .filter(cover -> cover.getStatus() == IllustrationStatus.GENERATING
                        || cover.getStatus() == IllustrationStatus.PENDING);
        if (coverOpt.isEmpty()) {
            return 0;
        }
        BookCoverEntity cover = coverOpt.get();
        cover.setStatus(IllustrationStatus.PENDING);
        cover.setRetryCount(0);
        cover.setNextRetryAt(null);
        clearCoverLease(cover);
        bookCoverRepository.save(cover);
        generationQueue.offer(new CoverRequest(bookId));
        return 1;
    }

    private void processQueue() {
        log.info("Book cover queue processor thread started");
        int processedCount = 0;
        while (running) {
            try {
                CoverRequest request = generationQueue.take();
                if (cacheOnly) {
                    log.info("Skipping queued book cover request in cache-only mode for book {}", request.bookId());
                    continue;
                }
                processedCount++;
                log.info("Processing book cover request #{} for book: {}", processedCount, request.bookId());
                generateCover(request.bookId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Book cover queue processor thread interrupted");
                break;
            } catch (Exception e) {
                log.error("Error processing book cover queue", e);
            }
        }
        log.info("Book cover queue processor thread stopped after processing {} requests", processedCount);
    }

    private void generateCover(String bookId) {
        if (!tryClaimGenerationLease(bookId)) {
            log.debug("Skipping book cover generation for book {} because lease claim failed", bookId);
            rescheduleDeferredRetryIfNeeded(bookId);
            return;
        }

        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            self.handleGenerationFailure(bookId, "Book not found", false);
            return;
        }

        try {
            String prompt = resolvePrompt(bookId, book);
            self.updateCoverPrompt(bookId, prompt);

            String cacheKey = assetKeyService.buildBookCoverKey(book);
            String filename = bookCoverImageGeneratorService.generateBookCover(prompt, "cover_" + bookId, cacheKey);
            self.updateCoverStatus(bookId, IllustrationStatus.COMPLETED, filename, null);
            log.info("Book cover completed for book: {} via {}", bookId, bookCoverImageGeneratorService.getProviderName());
        } catch (Exception e) {
            log.error("Failed to generate book cover for book: {}", bookId, e);
            self.handleGenerationFailure(bookId, e.getMessage(), true);
        }
    }

    private String resolvePrompt(String bookId, BookEntity book) {
        Optional<BookCoverEntity> coverOpt = bookCoverRepository.findByBookId(bookId);
        String override = coverOpt.map(BookCoverEntity::getPromptOverride).map(String::trim).orElse("");
        if (!override.isBlank()) {
            return override;
        }
        String existingPrompt = coverOpt.map(BookCoverEntity::getGeneratedPrompt).map(String::trim).orElse("");
        if (!existingPrompt.isBlank()) {
            return existingPrompt;
        }
        IllustrationSettings style = illustrationService.getOrAnalyzeBookStyle(bookId, false);
        return buildCoverPrompt(book, style);
    }

    private String buildCoverPrompt(BookEntity book, IllustrationSettings style) {
        String setting = style == null || style.setting() == null ? "" : style.setting();
        String prefix = style == null || style.promptPrefix() == null ? "classic literary illustration," : style.promptPrefix();
        String description = book.getDescription() == null ? "" : book.getDescription();
        if (description.length() > 500) {
            description = description.substring(0, 500);
        }
        return String.format(
                "%s text-free illustrated book cover artwork for %s by %s. One bold central subject, simple silhouette, strong contrast, rich color palette, painterly literary illustration, limited fine detail, readable at small thumbnail size. No title text, no author text, no words, no letters, no typography, no logos. Avoid tiny decorative borders, printed paper texture, dense background detail, and generic unrelated portraits. Setting: %s. Themes: %s",
                prefix,
                book.getTitle(),
                book.getAuthor(),
                setting,
                description
        );
    }

    @Transactional
    public void updateCoverStatus(String bookId, IllustrationStatus status, String filename, String errorMessage) {
        BookCoverEntity cover = bookCoverRepository.findByBookId(bookId).orElse(null);
        if (cover == null) {
            log.warn("Cannot update cover status: cover not found for book {}", bookId);
            return;
        }
        cover.setStatus(status);
        if (filename != null) {
            cover.setImageFilename(filename);
        }
        if (errorMessage != null) {
            cover.setErrorMessage(errorMessage);
        }
        if (status == IllustrationStatus.COMPLETED) {
            cover.setCompletedAt(LocalDateTime.now());
            cover.setErrorMessage(null);
            if (cover.getCoverSource() == null || cover.getCoverSource().isBlank()) {
                cover.setCoverSource(cover.getPromptOverride() == null || cover.getPromptOverride().isBlank()
                        ? bookCoverImageGeneratorService.getProviderName()
                        : "prompt_override");
            }
        }
        if (status != IllustrationStatus.PENDING) {
            cover.setNextRetryAt(null);
        }
        if (status != IllustrationStatus.GENERATING) {
            clearCoverLease(cover);
        }
        bookCoverRepository.save(cover);
    }

    @Transactional
    public void updateCoverPrompt(String bookId, String prompt) {
        bookCoverRepository.findByBookId(bookId).ifPresent(cover -> {
            cover.setGeneratedPrompt(prompt);
            bookCoverRepository.save(cover);
        });
    }

    @Transactional
    public void handleGenerationFailure(String bookId, String errorMessage, boolean retryable) {
        BookCoverEntity cover = bookCoverRepository.findByBookId(bookId).orElse(null);
        if (cover == null) {
            log.warn("Cannot record book cover failure: cover not found for book {}", bookId);
            return;
        }

        int nextRetryCount = Math.max(0, cover.getRetryCount()) + 1;
        cover.setErrorMessage(errorMessage);
        cover.setRetryCount(nextRetryCount);
        clearCoverLease(cover);

        int configuredMaxAttempts = Math.max(1, maxRetryAttempts);
        if (retryable && nextRetryCount < configuredMaxAttempts) {
            long delayMs = computeRetryDelayMillis(nextRetryCount);
            cover.setStatus(IllustrationStatus.PENDING);
            cover.setNextRetryAt(LocalDateTime.now().plus(Duration.ofMillis(delayMs)));
            bookCoverRepository.save(cover);
            scheduleRetryRequest(new CoverRequest(bookId), delayMs);
            log.warn("Retrying book cover generation for book {} in {}s (attempt {}/{})",
                    bookId,
                    Math.max(1L, delayMs / 1000L),
                    nextRetryCount + 1,
                    configuredMaxAttempts);
            return;
        }

        cover.setStatus(IllustrationStatus.FAILED);
        cover.setNextRetryAt(null);
        bookCoverRepository.save(cover);
    }

    private boolean tryClaimGenerationLease(String bookId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiresAt = now.plusMinutes(Math.max(1, coverLeaseMinutes));
        int claimed = bookCoverRepository.claimGenerationLease(
                bookId,
                now,
                leaseExpiresAt,
                workerId,
                IllustrationStatus.PENDING,
                IllustrationStatus.GENERATING
        );
        return claimed > 0;
    }

    private void clearCoverLease(BookCoverEntity cover) {
        cover.setLeaseOwner(null);
        cover.setLeaseExpiresAt(null);
    }

    private void rescheduleDeferredRetryIfNeeded(String bookId) {
        bookCoverRepository.findByBookId(bookId).ifPresent(cover -> {
            if (cover.getStatus() != IllustrationStatus.PENDING || cover.getNextRetryAt() == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            if (!cover.getNextRetryAt().isAfter(now)) {
                generationQueue.offer(new CoverRequest(bookId));
                return;
            }
            long delayMs = Duration.between(now, cover.getNextRetryAt()).toMillis();
            scheduleRetryRequest(new CoverRequest(bookId), delayMs);
        });
    }

    private long computeRetryDelayMillis(int retryCount) {
        long baseSeconds = Math.max(1, initialRetryDelaySeconds);
        long maxSeconds = Math.max(baseSeconds, maxRetryDelaySeconds);
        long delaySeconds = baseSeconds;
        for (int i = 1; i < retryCount; i++) {
            delaySeconds = Math.min(maxSeconds, delaySeconds * 2);
        }
        return Math.max(1L, delaySeconds) * 1000L;
    }

    private void scheduleRetryRequest(CoverRequest request, long delayMs) {
        retryScheduler.schedule(() -> {
            if (running) {
                generationQueue.offer(request);
            }
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void enqueueAfterCommit(String bookId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    generationQueue.offer(new CoverRequest(bookId));
                    log.info("Queued book cover retry for book: {}", bookId);
                }
            });
            return;
        }
        generationQueue.offer(new CoverRequest(bookId));
    }

    private String normalizePromptOverride(String promptOverride) {
        if (promptOverride == null || promptOverride.isBlank()) {
            return null;
        }
        String trimmed = promptOverride.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    private void validatePngImage(byte[] imageData) {
        if (imageData == null || imageData.length < 8
                || (imageData[0] & 0xFF) != 0x89
                || imageData[1] != 0x50
                || imageData[2] != 0x4E
                || imageData[3] != 0x47
                || imageData[4] != 0x0D
                || imageData[5] != 0x0A
                || imageData[6] != 0x1A
                || imageData[7] != 0x0A) {
            throw new IllegalArgumentException("Manual book cover uploads must be PNG images.");
        }
    }

    private record CoverRequest(String bookId) {}

    public record CoverStatus(
            String bookId,
            IllustrationStatus status,
            String imageFilename,
            String generatedPrompt,
            String promptOverride,
            String coverSource,
            String errorMessage,
            LocalDateTime completedAt
    ) {}

    public record CoverGenerationResult(boolean success, String errorMessage) {
        public static CoverGenerationResult success(boolean generated) {
            return new CoverGenerationResult(true, generated ? "Generated book cover" : null);
        }

        public static CoverGenerationResult failure(String errorMessage) {
            return new CoverGenerationResult(false, errorMessage == null || errorMessage.isBlank()
                    ? "Book cover generation failed"
                    : errorMessage);
        }
    }
}
