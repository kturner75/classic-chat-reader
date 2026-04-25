package com.classicchatreader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.entity.ChapterRecapEntity;
import com.classicchatreader.entity.ChapterRecapStatus;
import com.classicchatreader.model.ChapterRecapPayload;
import com.classicchatreader.model.ChapterRecapResponse;
import com.classicchatreader.model.ChapterRecapStatusResponse;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.repository.ChapterRecapRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
public class ChapterRecapService {

    private static final Logger log = LoggerFactory.getLogger(ChapterRecapService.class);
    private static final ChapterRecapPayload EMPTY_PAYLOAD =
            new ChapterRecapPayload("", List.of(), List.of());

    private final ChapterRecapRepository chapterRecapRepository;
    private final ChapterRepository chapterRepository;
    private final ParagraphRepository paragraphRepository;
    private final CharacterRepository characterRepository;
    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper;
    private final RecapMetricsService recapMetricsService;
    private final BlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = true;

    @Value("${recap.generation.max-context-chars:6000}")
    private int maxContextChars;

    @Value("${recap.generation.stuck-threshold-minutes:15}")
    private int stuckThresholdMinutes;

    @Value("${recap.generation.lease-minutes:20}")
    private int recapLeaseMinutes;

    @Value("${recap.generation.worker-id:}")
    private String configuredWorkerId;

    @Value("${generation.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${generation.retry.initial-delay-seconds:30}")
    private int initialRetryDelaySeconds;

    @Value("${generation.retry.max-delay-seconds:600}")
    private int maxRetryDelaySeconds;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    private String workerId;

    public ChapterRecapService(
            ChapterRecapRepository chapterRecapRepository,
            ChapterRepository chapterRepository,
            ParagraphRepository paragraphRepository,
            CharacterRepository characterRepository,
            @Qualifier("recapReasoningLlmProvider") LlmProvider reasoningProvider,
            RecapMetricsService recapMetricsService,
            ObjectMapper objectMapper) {
        this.chapterRecapRepository = chapterRecapRepository;
        this.chapterRepository = chapterRepository;
        this.paragraphRepository = paragraphRepository;
        this.characterRepository = characterRepository;
        this.reasoningProvider = reasoningProvider;
        this.recapMetricsService = recapMetricsService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        workerId = (configuredWorkerId != null && !configuredWorkerId.isBlank())
                ? configuredWorkerId
                : "pdr-" + UUID.randomUUID();
        executor.submit(this::processQueue);
        log.info("Chapter recap service started with background queue processor (workerId={})", workerId);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
        retryScheduler.shutdownNow();
        log.info("Chapter recap service shutting down");
    }

    @Transactional(readOnly = true)
    public Optional<ChapterRecapResponse> getChapterRecap(String chapterId) {
        Optional<ChapterRecapEntity> recapOpt = chapterRecapRepository.findByChapterIdWithChapterAndBook(chapterId);
        if (recapOpt.isPresent()) {
            return Optional.of(toRecapResponse(recapOpt.get()));
        }

        return chapterRepository.findByIdWithBook(chapterId)
                .map(this::toMissingRecapResponse);
    }

    @Transactional(readOnly = true)
    public Optional<ChapterRecapStatusResponse> getChapterRecapStatus(String chapterId) {
        return getChapterRecap(chapterId)
                .map(recap -> new ChapterRecapStatusResponse(
                        recap.bookId(),
                        recap.chapterId(),
                        recap.status(),
                        recap.ready(),
                        recap.generatedAt(),
                        recap.updatedAt()
                ));
    }

    @Transactional(readOnly = true)
    public Optional<String> findBookIdForChapter(String chapterId) {
        return chapterRepository.findByIdWithBook(chapterId)
                .map(chapter -> chapter.getBook().getId());
    }

    public int getQueueDepth() {
        return requestQueue.size();
    }

    public boolean isQueueProcessorRunning() {
        return !executor.isShutdown() && !executor.isTerminated();
    }

    public boolean isProviderAvailable() {
        return reasoningProvider.isAvailable();
    }

    @Transactional
    public void requestChapterRecap(String chapterId) {
        if (cacheOnly) {
            log.info("Skipping recap request in cache-only mode for chapter {}", chapterId);
            return;
        }
        Optional<ChapterRecapEntity> existingRecap = chapterRecapRepository.findByChapterId(chapterId);
        if (existingRecap.isPresent()) {
            ChapterRecapEntity recap = existingRecap.get();
            ChapterRecapStatus status = recap.getStatus();
            if (status == null) {
                status = recap.getPayloadJson() != null && !recap.getPayloadJson().isBlank()
                        ? ChapterRecapStatus.COMPLETED
                        : ChapterRecapStatus.PENDING;
                recap.setStatus(status);
                chapterRecapRepository.save(recap);
            }

            if (status == ChapterRecapStatus.COMPLETED) {
                return;
            }
            if (status == ChapterRecapStatus.GENERATING && !isStuckGenerating(recap)) {
                return;
            }
            if (status == ChapterRecapStatus.GENERATING) {
                log.info("Re-queueing stuck recap generation for chapter {} last updated at {}",
                        chapterId, recap.getUpdatedAt());
            }

            recap.setStatus(ChapterRecapStatus.PENDING);
            recap.setRetryCount(0);
            recap.setNextRetryAt(null);
            clearRecapLease(recap);
            chapterRecapRepository.save(recap);
            queueRecapRequest(chapterId);
            return;
        }

        ChapterEntity chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterId));

        try {
            ChapterRecapEntity recap = new ChapterRecapEntity(chapter);
            recap.setStatus(ChapterRecapStatus.PENDING);
            chapterRecapRepository.save(recap);
            queueRecapRequest(chapterId);
        } catch (DataIntegrityViolationException e) {
            log.debug("Chapter {} recap already exists (race condition handled)", chapterId);
            queueRecapRequest(chapterId);
        }
    }

    @Transactional
    public int forceQueuePendingForBook(String bookId) {
        if (cacheOnly) {
            log.info("Skipping recap re-queue in cache-only mode for book {}", bookId);
            return 0;
        }
        List<ChapterRecapEntity> pendingRecaps = chapterRecapRepository
                .findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.PENDING);
        List<ChapterRecapEntity> nullStatusRecaps = chapterRecapRepository
                .findByChapterBookIdAndStatusIsNull(bookId);

        int queued = 0;
        for (ChapterRecapEntity recap : pendingRecaps) {
            if (offerIfNotQueued(recap.getChapter().getId())) {
                queued++;
            }
        }
        for (ChapterRecapEntity recap : nullStatusRecaps) {
            recap.setStatus(ChapterRecapStatus.PENDING);
            recap.setRetryCount(0);
            recap.setNextRetryAt(null);
            clearRecapLease(recap);
            chapterRecapRepository.save(recap);
            if (offerIfNotQueued(recap.getChapter().getId())) {
                queued++;
            }
        }
        return queued;
    }

    @Transactional
    public int resetAndRequeueStuckForBook(String bookId) {
        if (cacheOnly) {
            log.info("Skipping recap reset/re-queue in cache-only mode for book {}", bookId);
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, stuckThresholdMinutes));
        List<ChapterRecapEntity> stuckGenerating = chapterRecapRepository
                .findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.GENERATING).stream()
                .filter(recap -> recap.getUpdatedAt() == null || recap.getUpdatedAt().isBefore(cutoff))
                .toList();
        List<ChapterRecapEntity> stuckPending = chapterRecapRepository
                .findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.PENDING);
        List<ChapterRecapEntity> nullStatusRecaps = chapterRecapRepository
                .findByChapterBookIdAndStatusIsNull(bookId);

        for (ChapterRecapEntity recap : stuckGenerating) {
            recap.setStatus(ChapterRecapStatus.PENDING);
            recap.setRetryCount(0);
            recap.setNextRetryAt(null);
            clearRecapLease(recap);
            chapterRecapRepository.save(recap);
        }

        int requeued = 0;
        for (ChapterRecapEntity recap : stuckGenerating) {
            if (offerIfNotQueued(recap.getChapter().getId())) {
                requeued++;
            }
        }
        for (ChapterRecapEntity recap : stuckPending) {
            if (offerIfNotQueued(recap.getChapter().getId())) {
                requeued++;
            }
        }
        for (ChapterRecapEntity recap : nullStatusRecaps) {
            recap.setStatus(ChapterRecapStatus.PENDING);
            recap.setRetryCount(0);
            recap.setNextRetryAt(null);
            clearRecapLease(recap);
            chapterRecapRepository.save(recap);
            if (offerIfNotQueued(recap.getChapter().getId())) {
                requeued++;
            }
        }
        return requeued;
    }

    @Transactional
    public void saveGeneratedRecap(
            String chapterId,
            ChapterRecapPayload payload,
            String promptVersion,
            String modelName) {
        ChapterEntity chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterId));
        ChapterRecapEntity recap = chapterRecapRepository.findByChapterId(chapterId)
                .orElseGet(() -> new ChapterRecapEntity(chapter));

        ChapterRecapPayload normalizedPayload = normalizePayload(payload);
        recap.setStatus(ChapterRecapStatus.COMPLETED);
        recap.setNextRetryAt(null);
        clearRecapLease(recap);
        recap.setGeneratedAt(LocalDateTime.now());
        recap.setPromptVersion(promptVersion);
        recap.setModelName(modelName);
        recap.setPayloadJson(toJson(normalizedPayload));
        chapterRecapRepository.save(recap);
    }

    @Transactional
    public void updateRecapStatus(String chapterId, ChapterRecapStatus status) {
        chapterRecapRepository.findByChapterId(chapterId).ifPresent(recap -> {
            recap.setStatus(status);
            if (status != ChapterRecapStatus.PENDING) {
                recap.setNextRetryAt(null);
            }
            if (status != ChapterRecapStatus.GENERATING) {
                clearRecapLease(recap);
            }
            chapterRecapRepository.save(recap);
        });
    }

    private void queueRecapRequest(String chapterId) {
        if (offerIfNotQueued(chapterId)) {
            recapMetricsService.recordGenerationRequested();
            log.debug("Queued chapter recap generation for chapter: {}", chapterId);
        } else {
            log.debug("Skipped queueing duplicate chapter recap request: {}", chapterId);
        }
    }

    private boolean offerIfNotQueued(String chapterId) {
        if (requestQueue.contains(chapterId)) {
            return false;
        }
        return requestQueue.offer(chapterId);
    }

    private void processQueue() {
        while (running) {
            try {
                String chapterId = requestQueue.take();
                processChapterRecap(chapterId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing chapter recap queue", e);
            }
        }
    }

    private void processChapterRecap(String chapterId) {
        if (cacheOnly) {
            log.info("Skipping queued recap generation in cache-only mode for chapter {}", chapterId);
            return;
        }
        if (!tryClaimGenerationLease(chapterId)) {
            log.debug("Skipping recap generation for chapter {} because lease claim failed", chapterId);
            rescheduleDeferredRetryIfNeeded(chapterId);
            return;
        }
        Optional<ChapterEntity> chapterOpt = chapterRepository.findByIdWithBook(chapterId);
        if (chapterOpt.isEmpty()) {
            log.warn("Cannot generate recap; chapter not found: {}", chapterId);
            handleRecapFailure(chapterId, false);
            return;
        }

        ChapterEntity chapter = chapterOpt.get();
        long startedAtMs = System.currentTimeMillis();
        try {
            List<String> paragraphs = loadChapterParagraphs(chapter.getId());
            RecapGenerationResult result = generateRecapPayload(chapter, paragraphs);
            saveGeneratedRecap(chapterId, result.payload(), result.promptVersion(), result.modelName());
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAtMs);
            recapMetricsService.recordGenerationCompleted(
                    "v1-extractive".equals(result.promptVersion()),
                    durationMs
            );
            log.info("Generated recap for chapter {} ({})", chapter.getChapterIndex(), chapter.getTitle());
        } catch (Exception e) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAtMs);
            recapMetricsService.recordGenerationFailed(durationMs);
            log.error("Failed to generate recap for chapter {}", chapterId, e);
            handleRecapFailure(chapterId, true);
        }
    }

    private boolean tryClaimGenerationLease(String chapterId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiresAt = now.plusMinutes(Math.max(1, recapLeaseMinutes));
        int claimed = chapterRecapRepository.claimGenerationLease(
                chapterId,
                now,
                leaseExpiresAt,
                workerId,
                ChapterRecapStatus.PENDING,
                ChapterRecapStatus.GENERATING
        );
        return claimed > 0;
    }

    private RecapGenerationResult generateRecapPayload(ChapterEntity chapter, List<String> paragraphs) {
        if (!paragraphs.isEmpty() && reasoningProvider.isAvailable()) {
            try {
                ChapterRecapPayload llmPayload = buildLlmPayload(chapter, paragraphs);
                if (hasMeaningfulContent(llmPayload)) {
                    return new RecapGenerationResult(
                            llmPayload,
                            "v2-llm-json",
                            reasoningProvider.getProviderName()
                    );
                }
                log.warn("LLM recap payload was empty; falling back to extractive recap for chapter {}", chapter.getId());
            } catch (Exception e) {
                log.warn("LLM recap generation failed; using extractive fallback for chapter {}", chapter.getId(), e);
            }
        }

        return buildExtractiveFallbackResult(chapter, paragraphs);
    }

    private RecapGenerationResult buildExtractiveFallbackResult(ChapterEntity chapter, List<String> paragraphs) {
        try {
            return new RecapGenerationResult(
                    buildFallbackPayload(chapter, paragraphs),
                    "v1-extractive",
                    "local-extractive"
            );
        } catch (Exception e) {
            // Keep recap generation resilient even if optional fallback sources fail.
            log.error("Extractive recap fallback failed for chapter {}; using minimal fallback", chapter.getId(), e);
            return new RecapGenerationResult(
                    buildMinimalFallbackPayload(paragraphs),
                    "v0-minimal",
                    "local-minimal"
            );
        }
    }

    private boolean isStuckGenerating(ChapterRecapEntity recap) {
        if (recap == null || recap.getStatus() != ChapterRecapStatus.GENERATING) {
            return false;
        }
        LocalDateTime updatedAt = recap.getUpdatedAt();
        if (updatedAt == null) {
            return true;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, stuckThresholdMinutes));
        return updatedAt.isBefore(cutoff);
    }

    private void clearRecapLease(ChapterRecapEntity recap) {
        recap.setLeaseOwner(null);
        recap.setLeaseExpiresAt(null);
    }

    @Transactional
    public void handleRecapFailure(String chapterId, boolean retryable) {
        ChapterRecapEntity recap = chapterRecapRepository.findByChapterId(chapterId).orElse(null);
        if (recap == null) {
            log.warn("Cannot record recap failure: recap not found for chapter {}", chapterId);
            return;
        }

        int nextRetryCount = Math.max(0, recap.getRetryCount()) + 1;
        recap.setRetryCount(nextRetryCount);
        clearRecapLease(recap);

        int configuredMaxAttempts = Math.max(1, maxRetryAttempts);
        if (retryable && nextRetryCount < configuredMaxAttempts) {
            long delayMs = computeRetryDelayMillis(nextRetryCount);
            LocalDateTime nextRetryAt = LocalDateTime.now().plus(Duration.ofMillis(delayMs));
            recap.setStatus(ChapterRecapStatus.PENDING);
            recap.setNextRetryAt(nextRetryAt);
            chapterRecapRepository.save(recap);
            scheduleRetryRequest(chapterId, delayMs);
            log.warn(
                    "Retrying chapter recap for {} in {}s (attempt {}/{})",
                    chapterId,
                    Math.max(1L, delayMs / 1000L),
                    nextRetryCount + 1,
                    configuredMaxAttempts
            );
            return;
        }

        recap.setStatus(ChapterRecapStatus.FAILED);
        recap.setNextRetryAt(null);
        chapterRecapRepository.save(recap);
    }

    private void rescheduleDeferredRetryIfNeeded(String chapterId) {
        chapterRecapRepository.findByChapterId(chapterId).ifPresent(recap -> {
            if (recap.getStatus() != ChapterRecapStatus.PENDING || recap.getNextRetryAt() == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            if (!recap.getNextRetryAt().isAfter(now)) {
                offerIfNotQueued(chapterId);
                return;
            }
            long delayMs = Duration.between(now, recap.getNextRetryAt()).toMillis();
            scheduleRetryRequest(chapterId, delayMs);
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

    private void scheduleRetryRequest(String chapterId, long delayMs) {
        long normalizedDelayMs = Math.max(0L, delayMs);
        retryScheduler.schedule(() -> {
            if (running) {
                offerIfNotQueued(chapterId);
            }
        }, normalizedDelayMs, TimeUnit.MILLISECONDS);
    }

    private List<String> loadChapterParagraphs(String chapterId) {
        return paragraphRepository.findByChapterIdOrderByParagraphIndex(chapterId).stream()
                .map(p -> p.getContent() == null ? "" : p.getContent().trim())
                .filter(s -> !s.isBlank())
                .toList();
    }

    private ChapterRecapPayload buildLlmPayload(ChapterEntity chapter, List<String> paragraphs) {
        String bookId = chapter.getBook().getId();
        List<String> knownCharacters = characterRepository
                .findByBookIdUpToChapter(bookId, chapter.getChapterIndex()).stream()
                .map(CharacterEntity::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        set -> set.stream().limit(30).toList()
                ));

        String chapterContext = buildChapterContext(paragraphs);
        String knownCharactersContext = knownCharacters.isEmpty()
                ? "(none yet)"
                : String.join(", ", knownCharacters);

        String prompt = String.format("""
            Generate a spoiler-safe chapter recap for a reader.

            BOOK: %s by %s
            CHAPTER INDEX: %d
            CHAPTER TITLE: %s

            STRICT SAFETY RULES:
            - Use ONLY details from this chapter context.
            - Do NOT infer events from future chapters.
            - If uncertain, omit the detail.
            - Keep claims factual and specific to the provided text.

            KNOWN CHARACTERS UP TO THIS CHAPTER:
            %s

            CHAPTER CONTEXT:
            %s

            OUTPUT REQUIREMENTS:
            - Return ONLY valid JSON (no markdown, no prose before/after).
            - shortSummary: 1-3 sentences, max 320 characters.
            - keyEvents: 2-5 concise bullet-style strings.
            - characterDeltas: 0-4 items. Include only characters whose state/role/intent changes in this chapter.

            JSON SCHEMA:
            {
              "shortSummary": "string",
              "keyEvents": ["string"],
              "characterDeltas": [
                {"characterName": "string", "delta": "string"}
              ]
            }
            """,
                chapter.getBook().getTitle(),
                chapter.getBook().getAuthor(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                knownCharactersContext,
                chapterContext
        );

        String generated = reasoningProvider.generate(prompt, LlmOptions.full(0.25, 0.9, 700));
        String json = extractJsonObject(generated);
        try {
            ChapterRecapPayload payload = objectMapper.readValue(json, ChapterRecapPayload.class);
            return normalizePayload(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON recap response from LLM provider", e);
        }
    }

    private String buildChapterContext(List<String> paragraphs) {
        StringBuilder context = new StringBuilder();
        int used = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            String line = "[" + i + "] " + trimToLength(paragraphs.get(i), 360) + "\n";
            if (used + line.length() > maxContextChars) {
                break;
            }
            context.append(line);
            used += line.length();
        }
        if (context.isEmpty()) {
            return "(empty chapter context)";
        }
        return context.toString();
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("No recap response returned from provider");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        throw new IllegalArgumentException("No JSON object found in recap response");
    }

    private boolean hasMeaningfulContent(ChapterRecapPayload payload) {
        if (payload == null) {
            return false;
        }
        return !(payload.shortSummary() == null || payload.shortSummary().isBlank())
                || (payload.keyEvents() != null && !payload.keyEvents().isEmpty())
                || (payload.characterDeltas() != null && !payload.characterDeltas().isEmpty());
    }

    private ChapterRecapPayload buildFallbackPayload(ChapterEntity chapter, List<String> paragraphs) {
        if (paragraphs.isEmpty()) {
            paragraphs = loadChapterParagraphs(chapter.getId());
        }

        String shortSummary = buildShortSummary(paragraphs);
        List<String> keyEvents = buildKeyEvents(paragraphs, shortSummary);
        List<ChapterRecapPayload.CharacterDelta> deltas = characterRepository
                .findByBookIdAndFirstChapterIdOrderByFirstParagraphIndex(chapter.getBook().getId(), chapter.getId()).stream()
                .limit(4)
                .map(this::toCharacterDelta)
                .toList();

        return new ChapterRecapPayload(shortSummary, keyEvents, deltas);
    }

    private ChapterRecapPayload buildMinimalFallbackPayload(List<String> paragraphs) {
        String shortSummary = buildShortSummary(paragraphs);
        List<String> keyEvents = buildKeyEvents(paragraphs, shortSummary);
        return new ChapterRecapPayload(shortSummary, keyEvents, List.of());
    }

    private record RecapGenerationResult(
            ChapterRecapPayload payload,
            String promptVersion,
            String modelName) {
    }

    private String buildShortSummary(List<String> paragraphs) {
        if (paragraphs.isEmpty()) {
            return "";
        }

        String summary = paragraphs.stream()
                .limit(2)
                .map(this::firstSentence)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
        return trimToLength(summary, 320);
    }

    private List<String> buildKeyEvents(List<String> paragraphs, String shortSummary) {
        List<String> events = paragraphs.stream()
                .map(this::firstSentence)
                .filter(s -> !s.isBlank())
                .map(sentence -> trimToLength(sentence, 180))
                .distinct()
                .limit(4)
                .toList();

        if (!events.isEmpty()) {
            return events;
        }
        if (!shortSummary.isBlank()) {
            return List.of(trimToLength(shortSummary, 180));
        }
        return List.of();
    }

    private ChapterRecapPayload.CharacterDelta toCharacterDelta(CharacterEntity character) {
        String detail = firstSentence(character.getDescription());
        if (detail.isBlank()) {
            detail = "Is introduced in this chapter.";
        } else {
            detail = "Introduced: " + trimToLength(detail, 140);
        }
        return new ChapterRecapPayload.CharacterDelta(character.getName(), detail);
    }

    private String firstSentence(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Arrays.stream(text.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
        if (normalized.isBlank()) {
            return "";
        }

        int sentenceBreak = -1;
        for (char marker : new char[]{'.', '!', '?'}) {
            int idx = normalized.indexOf(marker);
            if (idx > 0 && (sentenceBreak < 0 || idx < sentenceBreak)) {
                sentenceBreak = idx;
            }
        }
        if (sentenceBreak > 0) {
            return normalized.substring(0, sentenceBreak + 1);
        }
        return trimToLength(normalized, 180);
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength).trim() + "...";
    }

    private ChapterRecapResponse toMissingRecapResponse(ChapterEntity chapter) {
        return new ChapterRecapResponse(
                chapter.getBook().getId(),
                chapter.getId(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                "MISSING",
                false,
                null,
                null,
                null,
                null,
                EMPTY_PAYLOAD
        );
    }

    private ChapterRecapResponse toRecapResponse(ChapterRecapEntity entity) {
        ChapterRecapStatus status = entity.getStatus() != null ? entity.getStatus() : ChapterRecapStatus.PENDING;
        ChapterRecapPayload payload = toPayload(entity.getPayloadJson());
        ChapterEntity chapter = entity.getChapter();

        return new ChapterRecapResponse(
                chapter.getBook().getId(),
                chapter.getId(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                status.name(),
                status == ChapterRecapStatus.COMPLETED,
                entity.getGeneratedAt(),
                entity.getUpdatedAt(),
                entity.getPromptVersion(),
                entity.getModelName(),
                payload
        );
    }

    private ChapterRecapPayload toPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return EMPTY_PAYLOAD;
        }
        try {
            ChapterRecapPayload payload = objectMapper.readValue(payloadJson, ChapterRecapPayload.class);
            return normalizePayload(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse recap payload JSON", e);
            return EMPTY_PAYLOAD;
        }
    }

    private String toJson(ChapterRecapPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize recap payload", e);
        }
    }

    private ChapterRecapPayload normalizePayload(ChapterRecapPayload payload) {
        if (payload == null) {
            return EMPTY_PAYLOAD;
        }

        String shortSummary = payload.shortSummary() == null ? "" : payload.shortSummary().trim();
        List<String> keyEvents = payload.keyEvents() == null
                ? List.of()
                : payload.keyEvents().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<ChapterRecapPayload.CharacterDelta> characterDeltas = payload.characterDeltas() == null
                ? List.of()
                : payload.characterDeltas().stream()
                .filter(Objects::nonNull)
                .map(delta -> new ChapterRecapPayload.CharacterDelta(
                        delta.characterName() == null ? "" : delta.characterName().trim(),
                        delta.delta() == null ? "" : delta.delta().trim()))
                .filter(delta -> !delta.characterName().isEmpty() && !delta.delta().isEmpty())
                .toList();

        return new ChapterRecapPayload(shortSummary, keyEvents, characterDeltas);
    }
}
