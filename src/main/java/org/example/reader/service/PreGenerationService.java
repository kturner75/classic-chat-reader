package org.example.reader.service;

import org.example.reader.entity.CharacterStatus;
import org.example.reader.entity.ChapterEntity;
import org.example.reader.entity.ChapterAnalysisStatus;
import org.example.reader.entity.ChapterRecapStatus;
import org.example.reader.entity.IllustrationStatus;
import org.example.reader.model.Book;
import org.example.reader.repository.BookRepository;
import org.example.reader.repository.CharacterRepository;
import org.example.reader.repository.ChapterAnalysisRepository;
import org.example.reader.repository.ChapterRecapRepository;
import org.example.reader.repository.ChapterRepository;
import org.example.reader.repository.IllustrationRepository;
import org.example.reader.service.BookImportService.ImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Service for pre-generating all assets (illustrations and character portraits) for a book.
 * Handles importing the book if missing, analyzing style, and queuing all generation tasks.
 */
@Service
public class PreGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PreGenerationService.class);

    private final BookImportService bookImportService;
    private final BookStorageService bookStorageService;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterAnalysisRepository chapterAnalysisRepository;
    private final ChapterRecapRepository chapterRecapRepository;
    private final IllustrationRepository illustrationRepository;
    private final CharacterRepository characterRepository;
    private final IllustrationService illustrationService;
    private final CharacterService characterService;
    private final CharacterPrefetchService characterPrefetchService;
    private final ChapterRecapService chapterRecapService;

    @Value("${pregen.poll-interval-seconds:10}")
    private int pollIntervalSeconds;

    @Value("${pregen.max-wait-minutes:120}")
    private int maxWaitMinutes;

    @Value("${pregen.stall-threshold-polls:30}")
    private int stallThresholdPolls;

    @Value("${pregen.cooldown-every-images:100}")
    private int cooldownEveryImages;

    @Value("${pregen.image-cooldown-minutes:3}")
    private int imageCooldownMinutes;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    public PreGenerationService(
            BookImportService bookImportService,
            BookStorageService bookStorageService,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            ChapterAnalysisRepository chapterAnalysisRepository,
            ChapterRecapRepository chapterRecapRepository,
            IllustrationRepository illustrationRepository,
            CharacterRepository characterRepository,
            IllustrationService illustrationService,
            CharacterService characterService,
            CharacterPrefetchService characterPrefetchService,
            ChapterRecapService chapterRecapService) {
        this.bookImportService = bookImportService;
        this.bookStorageService = bookStorageService;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.chapterAnalysisRepository = chapterAnalysisRepository;
        this.chapterRecapRepository = chapterRecapRepository;
        this.illustrationRepository = illustrationRepository;
        this.characterRepository = characterRepository;
        this.illustrationService = illustrationService;
        this.characterService = characterService;
        this.characterPrefetchService = characterPrefetchService;
        this.chapterRecapService = chapterRecapService;
    }

    public record PreGenResult(
            boolean success,
            String bookId,
            String bookTitle,
            String message,
            int chaptersProcessed,
            int illustrationsCompleted,
            int illustrationsFailed,
            int portraitsCompleted,
            int portraitsFailed,
            int recapsCompleted,
            int recapsFailed,
            int newIllustrations,
            int newPortraits,
            int newRecaps
    ) {
        public static PreGenResult failure(String message) {
            return new PreGenResult(false, null, null, message, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Pre-generate all assets for a book by its Gutenberg ID.
     * Will import the book if not already present.
     */
    public PreGenResult preGenerateByGutenbergId(int gutenbergId) {
        return preGenerateByGutenbergId(gutenbergId, true);
    }

    public PreGenResult preGenerateImagesByGutenbergId(int gutenbergId) {
        return preGenerateByGutenbergId(gutenbergId, false);
    }

    private PreGenResult preGenerateByGutenbergId(int gutenbergId, boolean includeRecaps) {
        if (cacheOnly) {
            return PreGenResult.failure("Pre-generation is unavailable in cache-only mode.");
        }
        log.info("Starting {}pre-generation for Gutenberg ID: {}",
                includeRecaps ? "" : "images-only ",
                gutenbergId);

        // Check if already imported
        String sourceId = String.valueOf(gutenbergId);
        Optional<Book> existingBook = bookStorageService.findBySource("gutenberg", sourceId);

        String bookId;
        String bookTitle;

        if (existingBook.isPresent()) {
            bookId = existingBook.get().id();
            bookTitle = existingBook.get().title();
            log.info("Book already imported: '{}' ({})", bookTitle, bookId);
        } else {
            log.info("Importing book from Gutenberg ID: {}", gutenbergId);
            ImportResult importResult = bookImportService.importBook(gutenbergId);
            if (!importResult.success()) {
                log.error("Failed to import book: {}", importResult.message());
                return PreGenResult.failure("Import failed: " + importResult.message());
            }
            bookId = importResult.bookId();
            bookTitle = bookRepository.findById(bookId).map(b -> b.getTitle()).orElse("Unknown");
            log.info("Successfully imported '{}' with {} chapters", bookTitle, importResult.chapterCount());
        }

        return preGenerateForBook(bookId, includeRecaps);
    }

    /**
     * Pre-generate recap assets only for a book by Gutenberg ID.
     * Will import the book if not already present.
     */
    public PreGenResult preGenerateRecapsByGutenbergId(int gutenbergId) {
        if (cacheOnly) {
            return PreGenResult.failure("Pre-generation is unavailable in cache-only mode.");
        }
        log.info("Starting recap-only pre-generation for Gutenberg ID: {}", gutenbergId);

        String sourceId = String.valueOf(gutenbergId);
        Optional<Book> existingBook = bookStorageService.findBySource("gutenberg", sourceId);

        String bookId;
        String bookTitle;

        if (existingBook.isPresent()) {
            bookId = existingBook.get().id();
            bookTitle = existingBook.get().title();
            log.info("Book already imported: '{}' ({})", bookTitle, bookId);
        } else {
            log.info("Importing book from Gutenberg ID: {}", gutenbergId);
            ImportResult importResult = bookImportService.importBook(gutenbergId);
            if (!importResult.success()) {
                log.error("Failed to import book: {}", importResult.message());
                return PreGenResult.failure("Import failed: " + importResult.message());
            }
            bookId = importResult.bookId();
            bookTitle = bookRepository.findById(bookId).map(b -> b.getTitle()).orElse("Unknown");
            log.info("Successfully imported '{}' with {} chapters", bookTitle, importResult.chapterCount());
        }

        return preGenerateRecapsForBook(bookId);
    }

    /**
     * Pre-generate all assets for a book that's already in the database.
     */
    public PreGenResult preGenerateForBook(String bookId) {
        return preGenerateForBook(bookId, true);
    }

    public PreGenResult preGenerateImagesForBook(String bookId) {
        return preGenerateForBook(bookId, false);
    }

    private PreGenResult preGenerateForBook(String bookId, boolean includeRecaps) {
        if (cacheOnly) {
            return PreGenResult.failure("Pre-generation is unavailable in cache-only mode.");
        }
        var book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return PreGenResult.failure("Book not found: " + bookId);
        }

        String bookTitle = book.getTitle();
        log.info("Starting {}pre-generation for '{}' by {}",
                includeRecaps ? "" : "images-only ",
                bookTitle,
                book.getAuthor());

        int preIllustrationsTotal = illustrationRepository
                .findByChapterBookIdAndStatus(bookId, IllustrationStatus.COMPLETED).size()
                + illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.FAILED).size();
        int prePortraitsTotal = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.COMPLETED).size()
                + characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.FAILED).size();
        int preRecapsTotal = chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.COMPLETED).size()
                + chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.FAILED).size();

        int totalSteps = includeRecaps ? 4 : 3;

        // Step 1: Analyze book style (if not already done)
        log.info("[1/{}] Analyzing book illustration style...", totalSteps);
        illustrationService.getOrAnalyzeBookStyle(bookId, false);

        // Step 2: Prefetch main characters
        log.info("[2/{}] Prefetching main characters...", totalSteps);
        characterPrefetchService.prefetchCharactersForBook(bookId);

        // Step 3: Queue all chapter illustrations and character analysis
        List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(bookId);
        log.info("[3/{}] Queuing generation for {} chapters...", totalSteps, chapters.size());

        for (ChapterEntity chapter : chapters) {
            illustrationService.requestIllustration(chapter.getId());
            characterService.requestChapterAnalysis(chapter.getId());
            if (includeRecaps) {
                chapterRecapService.requestChapterRecap(chapter.getId());
            }
        }

        // Force-queue any items that were already pending (from previous runs)
        // This ensures they get processed even if they're less than 5 minutes old
        int illustrationsRequeued = illustrationService.forceQueuePendingForBook(bookId);
        int portraitsRequeued = characterService.forceQueuePendingPortraitsForBook(bookId);
        int failedPortraitsRequeued = characterService.retryFailedPortraitsForBook(bookId);
        int analysesRequeued = characterService.forceQueuePendingAnalysesForBook(bookId);
        int recapsRequeued = includeRecaps ? chapterRecapService.forceQueuePendingForBook(bookId) : 0;
        if (illustrationsRequeued > 0 || portraitsRequeued > 0 || failedPortraitsRequeued > 0
                || analysesRequeued > 0 || recapsRequeued > 0) {
            log.info("Re-queued {} illustrations, {} pending portraits, {} failed portraits, {} analyses, and {} recaps from previous state",
                    illustrationsRequeued, portraitsRequeued, failedPortraitsRequeued, analysesRequeued, recapsRequeued);
        }

        // Step 4: Wait for all generation to complete
        log.info("[{}/{}] Waiting for generation to complete (polling every {}s, max {}min)...",
                totalSteps,
                totalSteps,
                pollIntervalSeconds, maxWaitMinutes);
        if (cooldownEveryImages > 0 && imageCooldownMinutes > 0) {
            log.info("  Periodic cooldown: {}min every {} images", imageCooldownMinutes, cooldownEveryImages);
        }

        long startTime = System.currentTimeMillis();
        long maxWaitMs = maxWaitMinutes * 60 * 1000L;

        int lastIllustrationsPending = -1;
        int lastPortraitsPending = -1;
        int lastAnalysesPending = -1;
        int lastRecapsPending = -1;
        int stallCount = 0;
        final int stallThreshold = Math.max(1, stallThresholdPolls);

        // Track images completed for periodic cooldown
        int imagesSinceLastCooldown = 0;
        int lastTotalCompleted = preIllustrationsTotal + prePortraitsTotal;

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            // Count pending/generating illustrations
            int illustrationsPending = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.PENDING).size();
            int illustrationsGenerating = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.GENERATING).size();

            // Count pending/generating portraits
            int portraitsPending = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.PENDING).size();
            int portraitsGenerating = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.GENERATING).size();

            // Count pending/generating chapter analyses
            int analysesPending = chapterAnalysisRepository.findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.PENDING).size();
            int analysesPendingNull = chapterAnalysisRepository.findByChapterBookIdAndStatusIsNull(bookId).size();
            int analysesGenerating = chapterAnalysisRepository.findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.GENERATING).size();

            // Count pending/generating recaps
            int recapsPending = includeRecaps
                    ? chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.PENDING).size()
                    : 0;
            int recapsPendingNull = includeRecaps
                    ? chapterRecapRepository.findByChapterBookIdAndStatusIsNull(bookId).size()
                    : 0;
            int recapsGenerating = includeRecaps
                    ? chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.GENERATING).size()
                    : 0;

            int totalPending = illustrationsPending + illustrationsGenerating
                    + portraitsPending + portraitsGenerating
                    + analysesPending + analysesPendingNull + analysesGenerating
                    + recapsPending + recapsPendingNull + recapsGenerating;

            // Log progress if changed
            if (illustrationsPending + illustrationsGenerating != lastIllustrationsPending ||
                    portraitsPending + portraitsGenerating != lastPortraitsPending ||
                    analysesPending + analysesPendingNull + analysesGenerating != lastAnalysesPending ||
                    recapsPending + recapsPendingNull + recapsGenerating != lastRecapsPending) {
                log.info("  Progress: {} illustrations pending/generating, {} portraits pending/generating, {} analyses pending/generating, {} recaps pending/generating",
                        illustrationsPending + illustrationsGenerating,
                        portraitsPending + portraitsGenerating,
                        analysesPending + analysesPendingNull + analysesGenerating,
                        recapsPending + recapsPendingNull + recapsGenerating);
                lastIllustrationsPending = illustrationsPending + illustrationsGenerating;
                lastPortraitsPending = portraitsPending + portraitsGenerating;
                lastAnalysesPending = analysesPending + analysesPendingNull + analysesGenerating;
                lastRecapsPending = recapsPending + recapsPendingNull + recapsGenerating;
                stallCount = 0; // Reset stall counter on progress
            } else if (totalPending > 0) {
                stallCount++;
                if (stallCount >= stallThreshold) {
                    log.warn("Generation appears stalled. Re-queuing {} stuck items...", totalPending);
                    // Reset stuck GENERATING items and re-queue everything
                    int illustrationsReset = illustrationService.resetAndRequeueStuckForBook(bookId);
                    int portraitsReset = characterService.resetAndRequeueStuckPortraitsForBook(bookId);
                    int analysesReset = characterService.resetAndRequeueStuckAnalysesForBook(bookId);
                    int recapsReset = includeRecaps ? chapterRecapService.resetAndRequeueStuckForBook(bookId) : 0;
                    log.info("Reset and re-queued {} illustrations, {} portraits, {} analyses, and {} recaps",
                            illustrationsReset, portraitsReset, analysesReset, recapsReset);
                    stallCount = 0;
                }
            }

            if (totalPending == 0) {
                log.info("All generation tasks completed!");
                break;
            }

            // Check for periodic cooldown based on images completed
            if (cooldownEveryImages > 0 && imageCooldownMinutes > 0) {
                int currentCompleted = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.COMPLETED).size()
                        + illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.FAILED).size()
                        + characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.COMPLETED).size()
                        + characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.FAILED).size();
                int newlyCompleted = currentCompleted - lastTotalCompleted;
                imagesSinceLastCooldown += newlyCompleted;
                lastTotalCompleted = currentCompleted;

                if (imagesSinceLastCooldown >= cooldownEveryImages && totalPending > 0) {
                    log.info("Cooldown triggered: {} images completed since last cooldown (threshold: {})",
                            imagesSinceLastCooldown, cooldownEveryImages);
                    log.info("Cooling down for {} minutes to prevent API overheating...", imageCooldownMinutes);
                    try {
                        Thread.sleep(imageCooldownMinutes * 60 * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Cooldown interrupted");
                    }
                    imagesSinceLastCooldown = 0;
                    log.info("Cooldown complete, resuming generation...");
                }
            }

            try {
                Thread.sleep(pollIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Pre-generation interrupted");
                break;
            }
        }

        // Collect final stats
        int illustrationsCompleted = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.COMPLETED).size();
        int illustrationsFailed = illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.FAILED).size();
        int portraitsCompleted = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.COMPLETED).size();
        int portraitsFailed = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.FAILED).size();
        int recapsCompleted = chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.COMPLETED).size();
        int recapsFailed = chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.FAILED).size();
        int newIllustrations = Math.max(0, (illustrationsCompleted + illustrationsFailed) - preIllustrationsTotal);
        int newPortraits = Math.max(0, (portraitsCompleted + portraitsFailed) - prePortraitsTotal);
        int newRecaps = Math.max(0, (recapsCompleted + recapsFailed) - preRecapsTotal);

        long elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
        log.info("Pre-generation completed for '{}' in {} minutes", bookTitle, elapsedMinutes);
        if (includeRecaps) {
            log.info("  Illustrations: {} completed, {} failed", illustrationsCompleted, illustrationsFailed);
            log.info("  Portraits: {} completed, {} failed", portraitsCompleted, portraitsFailed);
            log.info("  Recaps: {} completed, {} failed", recapsCompleted, recapsFailed);
        } else {
            log.info("  New image work: {} illustrations, {} portraits", newIllustrations, newPortraits);
            log.info("  Current totals: {} illustrations completed, {} portraits completed",
                    illustrationsCompleted, portraitsCompleted);
        }

        boolean allSuccess = illustrationsFailed == 0 && portraitsFailed == 0 && recapsFailed == 0;
        String message;
        if (allSuccess) {
            if (includeRecaps) {
                message = "All assets generated successfully";
            } else if (newIllustrations == 0 && newPortraits == 0) {
                message = "No new image assets required";
            } else {
                message = String.format("Generated %d new illustrations and %d new portraits",
                        newIllustrations, newPortraits);
            }
        } else if (includeRecaps) {
            message = String.format("%d illustrations failed, %d portraits failed, %d recaps failed",
                    illustrationsFailed, portraitsFailed, recapsFailed);
        } else {
            message = String.format("%d illustrations failed, %d portraits failed",
                    illustrationsFailed, portraitsFailed);
        }

        return new PreGenResult(
                allSuccess,
                bookId,
                bookTitle,
                message,
                chapters.size(),
                illustrationsCompleted,
                illustrationsFailed,
                portraitsCompleted,
                portraitsFailed,
                recapsCompleted,
                recapsFailed,
                newIllustrations,
                newPortraits,
                newRecaps
        );
    }

    /**
     * Pre-generate recap assets only for a book that's already in the database.
     */
    public PreGenResult preGenerateRecapsForBook(String bookId) {
        if (cacheOnly) {
            return PreGenResult.failure("Pre-generation is unavailable in cache-only mode.");
        }
        var book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return PreGenResult.failure("Book not found: " + bookId);
        }

        String bookTitle = book.getTitle();
        log.info("Starting recap-only pre-generation for '{}' by {}", bookTitle, book.getAuthor());

        int preRecapsTotal = chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.COMPLETED).size()
                + chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.FAILED).size();

        List<ChapterEntity> chapters = chapterRepository.findByBookIdOrderByChapterIndex(bookId);
        log.info("Queuing recap generation for {} chapters...", chapters.size());
        for (ChapterEntity chapter : chapters) {
            chapterRecapService.requestChapterRecap(chapter.getId());
        }

        int recapsRequeued = chapterRecapService.forceQueuePendingForBook(bookId);
        if (recapsRequeued > 0) {
            log.info("Re-queued {} recaps from previous pending state", recapsRequeued);
        }

        log.info("Waiting for recap generation to complete (polling every {}s, max {}min)...",
                pollIntervalSeconds, maxWaitMinutes);

        long startTime = System.currentTimeMillis();
        long maxWaitMs = maxWaitMinutes * 60 * 1000L;
        int lastRecapsPending = -1;
        int stallCount = 0;
        final int stallThreshold = Math.max(1, stallThresholdPolls);

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            int recapsPending = chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.PENDING).size();
            int recapsPendingNull = chapterRecapRepository.findByChapterBookIdAndStatusIsNull(bookId).size();
            int recapsGenerating = chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.GENERATING).size();
            int totalPending = recapsPending + recapsPendingNull + recapsGenerating;

            if (totalPending != lastRecapsPending) {
                log.info("  Progress: {} recaps pending/generating", totalPending);
                lastRecapsPending = totalPending;
                stallCount = 0;
            } else if (totalPending > 0) {
                stallCount++;
                if (stallCount >= stallThreshold) {
                    int recapsReset = chapterRecapService.resetAndRequeueStuckForBook(bookId);
                    log.info("Reset and re-queued {} recaps", recapsReset);
                    stallCount = 0;
                }
            }

            if (totalPending == 0) {
                log.info("All recap tasks completed!");
                break;
            }

            try {
                Thread.sleep(pollIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Recap pre-generation interrupted");
                break;
            }
        }

        int recapsCompleted = chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.COMPLETED).size();
        int recapsFailed = chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.FAILED).size();
        int newRecaps = Math.max(0, (recapsCompleted + recapsFailed) - preRecapsTotal);

        long elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
        log.info("Recap-only pre-generation completed for '{}' in {} minutes", bookTitle, elapsedMinutes);
        log.info("  Recaps: {} completed, {} failed", recapsCompleted, recapsFailed);

        boolean success = recapsFailed == 0;
        String message = success
                ? "Recaps generated successfully"
                : String.format("%d recaps failed", recapsFailed);

        return new PreGenResult(
                success,
                bookId,
                bookTitle,
                message,
                chapters.size(),
                0,
                0,
                0,
                0,
                recapsCompleted,
                recapsFailed,
                0,
                0,
                newRecaps
        );
    }
}
