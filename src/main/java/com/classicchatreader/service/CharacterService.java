package com.classicchatreader.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.classicchatreader.entity.*;
import com.classicchatreader.model.CharacterInfo;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.repository.*;
import com.classicchatreader.service.CharacterExtractionService.ExtractedCharacter;
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
import java.util.concurrent.TimeUnit;

@Service
public class CharacterService {

    private static final Logger log = LoggerFactory.getLogger(CharacterService.class);
    private static final Set<String> NAME_TITLES = Set.of(
            "mr", "mrs", "ms", "miss", "lady", "lord", "sir", "madam", "madame",
            "mme", "mlle", "dr", "doctor", "prof", "professor", "rev", "reverend",
            "capt", "captain", "col", "colonel", "major"
    );
    private static final Set<String> GENERIC_DESCRIPTORS = Set.of(
            "man", "woman", "boy", "girl", "child", "stranger", "servant", "maid",
            "butler", "sailor", "soldier", "officer", "guard", "driver", "porter",
            "passerby", "gentleman", "lady", "visitor", "neighbor"
    );
    private static final Set<String> GENERIC_DESCRIPTOR_TOKENS = Set.of(
            "man", "men", "woman", "women", "boy", "boys", "girl", "girls", "child", "children",
            "stranger", "strangers", "servant", "servants", "maid", "maids", "butler", "butlers",
            "sailor", "sailors", "soldier", "soldiers", "officer", "officers", "guard", "guards",
            "driver", "drivers", "porter", "porters", "passerby", "passersby", "gentleman",
            "gentlemen", "lady", "ladies", "visitor", "visitors", "neighbor", "neighbors",
            "people", "folk"
    );
    private static final Set<String> LEADING_ARTICLES = Set.of(
            "the ", "a ", "an ", "some ", "another ", "any "
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
    private final AssetKeyService assetKeyService;

    @Value("${character.secondary.max-per-book:40}")
    private int maxSecondaryPerBook;

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

    private String workerId;

    private final BlockingQueue<CharacterRequest> requestQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor();
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
        executor.submit(this::processQueue);
        log.info("Character service started with background queue processor (workerId={})", workerId);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
        retryScheduler.shutdownNow();
        log.info("Character service shutting down");
    }

    public boolean isAvailable() {
        return extractionService.isReasoningProviderAvailable() && comfyUIService.isAvailable();
    }

    public boolean isQueueProcessorRunning() {
        return !executor.isShutdown() && !executor.isTerminated();
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
        return characterRepository.findByBookIdOrderByCreatedAt(bookId).stream()
                .map(CharacterInfo::from)
                .collect(Collectors.toList());
    }

    public List<CharacterInfo> getCharactersUpToPosition(String bookId, int chapterIndex, int paragraphIndex) {
        return characterRepository.findByBookIdUpToPosition(bookId, chapterIndex, paragraphIndex).stream()
                .map(CharacterInfo::from)
                .collect(Collectors.toList());
    }

    public List<CharacterInfo> getNewlyCompletedSince(String bookId, LocalDateTime sinceTime) {
        return characterRepository.findNewlyCompletedSince(bookId, sinceTime).stream()
                .map(CharacterInfo::from)
                .collect(Collectors.toList());
    }

    public Optional<CharacterEntity> getCharacter(String characterId) {
        return characterRepository.findById(characterId);
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

    public Optional<String> getPortraitFilename(String characterId) {
        return characterRepository.findById(characterId)
                .filter(c -> c.getStatus() == CharacterStatus.COMPLETED)
                .map(CharacterEntity::getPortraitFilename);
    }

    private void processQueue() {
        log.info("Character queue processor thread started");
        int processedCount = 0;
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
                processedCount++;

                if (request instanceof AnalysisRequest ar) {
                    log.info("Processing character analysis #{} for chapter: {}", processedCount, ar.chapterId());
                    processChapterAnalysis(ar.chapterId());
                } else if (request instanceof PortraitRequest pr) {
                    log.info("Processing portrait generation #{} for character: {}", processedCount, pr.characterId());
                    generatePortrait(pr.characterId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Character queue processor thread interrupted");
                break;
            } catch (Exception e) {
                log.error("Error processing character queue", e);
            }
        }
        log.info("Character queue processor thread stopped after processing {} requests", processedCount);
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

        List<String> existingNames = characterRepository.findByBookIdOrderByCreatedAt(book.getId())
                .stream()
                .map(CharacterEntity::getName)
                .collect(Collectors.toList());
        Set<String> primaryNameIndex = buildPrimaryNameIndex(book.getId());
        Set<String> primaryTokenIndex = buildPrimaryTokenIndex(primaryNameIndex);
        long secondaryCount = characterRepository
                .countByBookIdAndCharacterType(book.getId(), CharacterType.SECONDARY);
        int remainingSecondarySlots = Math.max(0, maxSecondaryPerBook - (int) secondaryCount);

        try {
            String chapterContent = getChapterText(chapterId);

            List<ExtractedCharacter> extracted = extractionService.extractCharactersFromChapter(
                    book.getTitle(),
                    book.getAuthor(),
                    chapter.getTitle(),
                    chapterContent,
                    existingNames
            );

            if (remainingSecondarySlots <= 0) {
                log.info("Secondary character limit reached for '{}' (max {}), skipping new characters",
                        book.getTitle(), maxSecondaryPerBook);
                self.updateChapterAnalysisCount(chapterId, 0);
                return;
            }

            int createdCount = 0;
            for (ExtractedCharacter ec : extracted) {
                try {
                    if (!isClearlyNamed(ec.name())) {
                        log.debug("Skipping '{}' - name not clearly defined", ec.name());
                        continue;
                    }
                    if (isPossiblyConfusedWithPrimary(primaryNameIndex, primaryTokenIndex, ec.name())) {
                        log.debug("Skipping '{}' - matches primary character name", ec.name());
                        continue;
                    }
                    CharacterEntity character = self.createCharacter(
                            book, chapter, ec.name(), ec.description(), ec.approximateParagraphIndex()
                    );
                    if (character != null) {
                        requestQueue.offer(new PortraitRequest(character.getId()));
                        log.info("Created character '{}' and queued portrait generation", ec.name());
                        createdCount++;
                        remainingSecondarySlots--;
                        if (remainingSecondarySlots <= 0) {
                            log.info("Secondary character limit reached for '{}' (max {})",
                                    book.getTitle(), maxSecondaryPerBook);
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to create character '{}'", ec.name(), e);
                }
            }

            self.updateChapterAnalysisCount(chapterId, createdCount);
        } catch (Exception e) {
            log.error("Failed to analyze chapter {}", chapterId, e);
            self.handleAnalysisFailure(chapterId, true);
        }
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
        if (character.getPortraitFilename() != null && !character.getPortraitFilename().isBlank()) {
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

    private Set<String> buildPrimaryNameIndex(String bookId) {
        return characterRepository.findByBookIdOrderByCreatedAt(bookId)
                .stream()
                .filter(character -> character.getCharacterType() == CharacterType.PRIMARY)
                .map(CharacterEntity::getName)
                .map(this::normalizeName)
                .filter(normalized -> !normalized.isBlank())
                .collect(Collectors.toSet());
    }

    private Set<String> buildPrimaryTokenIndex(Set<String> primaryNameIndex) {
        return primaryNameIndex.stream()
                .flatMap(primary -> Arrays.stream(primary.split(" ")))
                .map(String::trim)
                .filter(token -> token.length() > 1)
                .collect(Collectors.toSet());
    }

    private boolean isPossiblyConfusedWithPrimary(Set<String> primaryNameIndex,
                                                  Set<String> primaryTokenIndex,
                                                  String name) {
        String normalizedNew = normalizeName(name);
        if (normalizedNew.isBlank()) {
            return true;
        }
        if (primaryNameIndex.contains(normalizedNew)) {
            return true;
        }
        if (isLastNameOnly(normalizedNew)) {
            return primaryNameIndex.stream()
                    .anyMatch(primary -> lastNameMatches(primary, normalizedNew));
        }
        return Arrays.stream(normalizedNew.split(" "))
                .anyMatch(primaryTokenIndex::contains);
    }

    private boolean isClearlyNamed(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase();
        for (String article : LEADING_ARTICLES) {
            if (lower.startsWith(article)) {
                return false;
            }
        }
        if (isGenericDescriptorPhrase(trimmed)) {
            return false;
        }
        String normalized = normalizeName(name);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.split(" ").length == 1 && GENERIC_DESCRIPTORS.contains(normalized)) {
            return false;
        }
        return true;
    }

    private boolean isGenericDescriptorPhrase(String name) {
        String normalized = normalizeName(name);
        if (normalized.isBlank()) {
            return true;
        }
        String[] normalizedTokens = normalized.split(" ");
        String lastToken = normalizedTokens[normalizedTokens.length - 1];
        if (!GENERIC_DESCRIPTOR_TOKENS.contains(lastToken)) {
            return false;
        }

        String[] originalTokens = name.trim().split("\\s+");
        int uppercaseTokens = 0;
        int uppercaseNonGenericTokens = 0;
        boolean firstTokenHasUppercase = false;
        for (int i = 0; i < originalTokens.length; i++) {
            String token = originalTokens[i];
            boolean hasUppercase = token.chars().anyMatch(Character::isUpperCase);
            if (!hasUppercase) {
                continue;
            }
            uppercaseTokens++;
            if (i == 0) {
                firstTokenHasUppercase = true;
            }
            String normalizedToken = normalizeName(token);
            if (!normalizedToken.isBlank() && !GENERIC_DESCRIPTOR_TOKENS.contains(normalizedToken)) {
                uppercaseNonGenericTokens++;
            }
        }

        if (uppercaseNonGenericTokens >= 2) {
            return false;
        }
        if (uppercaseNonGenericTokens == 1 && !(uppercaseTokens == 1 && firstTokenHasUppercase)) {
            return false;
        }

        return true;
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
        if (cacheOnly) {
            log.info("Skipping portrait generation in cache-only mode for character {}", characterId);
            return;
        }
        if (!tryClaimPortraitLease(characterId)) {
            log.debug("Skipping portrait generation for character {} because lease claim failed", characterId);
            rescheduleDeferredPortraitRetryIfNeeded(characterId);
            return;
        }
        CharacterEntity character = characterRepository.findByIdWithBookAndChapter(characterId).orElse(null);
        if (character == null) {
            log.warn("Character not found for portrait generation: {}", characterId);
            self.handlePortraitFailure(characterId, "Character not found", false);
            return;
        }

        try {
            BookEntity book = character.getBook();
            IllustrationSettings bookStyle = illustrationService.getOrAnalyzeBookStyle(book.getId(), false);

            String portraitPrompt = portraitService.generatePortraitPrompt(
                    book.getTitle(),
                    book.getAuthor(),
                    character.getName(),
                    character.getDescription(),
                    bookStyle
            );

            self.updatePortraitPrompt(characterId, portraitPrompt);

            String outputPrefix = "portrait_" + characterId;
            String cacheKey = buildPortraitCacheKey(character);
            String promptId = comfyUIService.submitPortraitWorkflow(portraitPrompt, outputPrefix, cacheKey);

            ComfyUIService.IllustrationResult result = comfyUIService.pollForPortraitCompletion(promptId);

            if (result.success()) {
                self.updateCharacterStatus(characterId, CharacterStatus.COMPLETED, cacheKey, null);
                log.info("Portrait completed for character: {}", character.getName());
            } else {
                self.handlePortraitFailure(characterId, result.errorMessage(), true);
                log.warn("Portrait generation failed for '{}': {}", character.getName(), result.errorMessage());
            }

        } catch (Exception e) {
            log.error("Failed to generate portrait for character: {}", character.getName(), e);
            self.handlePortraitFailure(characterId, e.getMessage(), true);
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
     * Also clears chapter analysis records so they can be re-analyzed.
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
            if (character.getPortraitFilename() != null) {
                if (comfyUIService.deletePortraitFile(character.getPortraitFilename())) {
                    deletedFiles++;
                }
            }
        }
        log.info("Deleted {} portrait files for book {}", deletedFiles, bookId);

        // Delete chapter analyses so chapters can be re-analyzed
        chapterAnalysisRepository.deleteByBookId(bookId);
        log.info("Deleted chapter analyses for book {}", bookId);

        // Delete characters from database
        int characterCount = characters.size();
        characterRepository.deleteByBookId(bookId);
        log.info("Deleted {} characters for book {}", characterCount, bookId);

        return characterCount;
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
            if (character.getPortraitFilename() == null || character.getPortraitFilename().isBlank()) {
                if (requestQueue.offer(new PortraitRequest(character.getId()))) {
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
            character.setStatus(CharacterStatus.PENDING);
            character.setRetryCount(0);
            character.setNextRetryAt(null);
            character.setErrorMessage(null);
            clearCharacterLease(character);
            characterRepository.save(character);
            if (character.getPortraitFilename() == null || character.getPortraitFilename().isBlank()) {
                if (requestQueue.offer(new PortraitRequest(character.getId()))) {
                    queued++;
                }
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
        if (cacheOnly) {
            log.info("Skipping portrait reset/re-queue in cache-only mode for book {}", bookId);
            return 0;
        }
        List<CharacterEntity> stuckGenerating = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.GENERATING);
        List<CharacterEntity> stuckPending = characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.PENDING);

        int reset = 0;
        for (CharacterEntity character : stuckGenerating) {
            character.setStatus(CharacterStatus.PENDING);
            character.setRetryCount(0);
            character.setNextRetryAt(null);
            clearCharacterLease(character);
            characterRepository.save(character);
            reset++;
        }

        // Re-queue all pending (including just-reset ones)
        int queued = 0;
        for (CharacterEntity character : stuckGenerating) {
            if (character.getPortraitFilename() == null || character.getPortraitFilename().isBlank()) {
                if (requestQueue.offer(new PortraitRequest(character.getId()))) {
                    queued++;
                }
            }
        }
        for (CharacterEntity character : stuckPending) {
            if (character.getPortraitFilename() == null || character.getPortraitFilename().isBlank()) {
                if (requestQueue.offer(new PortraitRequest(character.getId()))) {
                    queued++;
                }
            }
        }

        log.info("Reset {} stuck GENERATING portraits and queued {} total for book {}", reset, queued, bookId);
        return reset + stuckPending.size();
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

    private record PortraitRequest(String characterId) implements CharacterRequest {
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
            scheduleRetryRequest(new PortraitRequest(characterId), delayMs);
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
        characterRepository.findById(characterId).ifPresent(character -> {
            if (character.getStatus() != CharacterStatus.PENDING || character.getNextRetryAt() == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            if (!character.getNextRetryAt().isAfter(now)) {
                requestQueue.offer(new PortraitRequest(characterId));
                return;
            }
            long delayMs = Duration.between(now, character.getNextRetryAt()).toMillis();
            scheduleRetryRequest(new PortraitRequest(characterId), delayMs);
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
}
