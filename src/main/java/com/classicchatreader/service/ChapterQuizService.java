package com.classicchatreader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ChapterQuizEntity;
import com.classicchatreader.entity.ChapterQuizStatus;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.model.ChapterQuizGradeResponse;
import com.classicchatreader.model.ChapterQuizPayload;
import com.classicchatreader.model.ChapterQuizResponse;
import com.classicchatreader.model.ChapterQuizStatusResponse;
import com.classicchatreader.model.ChapterQuizViewPayload;
import com.classicchatreader.repository.ChapterQuizRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

@Service
public class ChapterQuizService {

    private static final Logger log = LoggerFactory.getLogger(ChapterQuizService.class);
    private static final ChapterQuizPayload EMPTY_PAYLOAD = new ChapterQuizPayload(List.of());
    private static final ChapterQuizViewPayload EMPTY_VIEW_PAYLOAD = new ChapterQuizViewPayload(List.of());

    private final ChapterQuizRepository chapterQuizRepository;
    private final ChapterRepository chapterRepository;
    private final ParagraphRepository paragraphRepository;
    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper;
    private final QuizProgressService quizProgressService;
    private final QuizMetricsService quizMetricsService;
    private final BlockingQueue<String> requestQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    @Value("${quiz.generation.max-context-chars:7000}")
    private int maxContextChars;

    @Value("${quiz.generation.questions-per-chapter:3}")
    private int questionsPerChapter;

    @Value("${quiz.generation.max-questions:5}")
    private int maxQuestions;

    @Value("${quiz.difficulty.ramp.enabled:true}")
    private boolean difficultyRampEnabled;

    @Value("${quiz.difficulty.ramp.chapter-step:6}")
    private int difficultyRampChapterStep;

    @Value("${quiz.difficulty.ramp.max-level:3}")
    private int difficultyRampMaxLevel;

    @Value("${quiz.difficulty.ramp.question-boost-per-level:1}")
    private int questionBoostPerDifficultyLevel;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    public ChapterQuizService(
            ChapterQuizRepository chapterQuizRepository,
            ChapterRepository chapterRepository,
            ParagraphRepository paragraphRepository,
            @Qualifier("quizReasoningLlmProvider") LlmProvider reasoningProvider,
            QuizProgressService quizProgressService,
            QuizMetricsService quizMetricsService,
            ObjectMapper objectMapper) {
        this.chapterQuizRepository = chapterQuizRepository;
        this.chapterRepository = chapterRepository;
        this.paragraphRepository = paragraphRepository;
        this.reasoningProvider = reasoningProvider;
        this.quizProgressService = quizProgressService;
        this.quizMetricsService = quizMetricsService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        executor.submit(this::processQueue);
        log.info("Chapter quiz service started with background queue processor");
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
        log.info("Chapter quiz service shutting down");
    }

    @Transactional(readOnly = true)
    public Optional<ChapterQuizResponse> getChapterQuiz(String chapterId) {
        Optional<ChapterQuizEntity> quizOpt = chapterQuizRepository.findByChapterIdWithChapterAndBook(chapterId);
        if (quizOpt.isPresent()) {
            return Optional.of(toQuizResponse(quizOpt.get()));
        }

        return chapterRepository.findByIdWithBook(chapterId)
                .map(this::toMissingQuizResponse);
    }

    @Transactional(readOnly = true)
    public Optional<ChapterQuizStatusResponse> getChapterQuizStatus(String chapterId) {
        return getChapterQuiz(chapterId)
                .map(quiz -> new ChapterQuizStatusResponse(
                        quiz.bookId(),
                        quiz.chapterId(),
                        quiz.status(),
                        quiz.ready(),
                        quiz.generatedAt(),
                        quiz.updatedAt()
                ));
    }

    @Transactional(readOnly = true)
    public Optional<String> findBookIdForChapter(String chapterId) {
        return chapterRepository.findByIdWithBook(chapterId)
                .map(chapter -> chapter.getBook().getId());
    }

    public boolean isProviderAvailable() {
        return reasoningProvider.isAvailable();
    }

    public int getQueueDepth() {
        return requestQueue.size();
    }

    public boolean isQueueProcessorRunning() {
        return !executor.isShutdown() && !executor.isTerminated();
    }

    @Transactional
    public void requestChapterQuiz(String chapterId) {
        if (cacheOnly) {
            log.info("Skipping quiz request in cache-only mode for chapter {}", chapterId);
            return;
        }
        Optional<ChapterQuizEntity> existingQuiz = chapterQuizRepository.findByChapterId(chapterId);
        if (existingQuiz.isPresent()) {
            ChapterQuizEntity quiz = existingQuiz.get();
            ChapterQuizStatus status = quiz.getStatus();
            if (status == null) {
                status = quiz.getPayloadJson() != null && !quiz.getPayloadJson().isBlank()
                        ? ChapterQuizStatus.COMPLETED
                        : ChapterQuizStatus.PENDING;
                quiz.setStatus(status);
                chapterQuizRepository.save(quiz);
            }

            if (status == ChapterQuizStatus.COMPLETED || status == ChapterQuizStatus.GENERATING) {
                return;
            }

            quiz.setStatus(ChapterQuizStatus.PENDING);
            chapterQuizRepository.save(quiz);
            queueQuizRequest(chapterId);
            return;
        }

        ChapterEntity chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterId));

        try {
            ChapterQuizEntity quiz = new ChapterQuizEntity(chapter);
            quiz.setStatus(ChapterQuizStatus.PENDING);
            chapterQuizRepository.save(quiz);
            queueQuizRequest(chapterId);
        } catch (DataIntegrityViolationException e) {
            log.debug("Chapter {} quiz already exists (race condition handled)", chapterId);
            queueQuizRequest(chapterId);
        }
    }

    @Transactional
    public void saveGeneratedQuiz(
            String chapterId,
            ChapterQuizPayload payload,
            String promptVersion,
            String modelName) {
        ChapterEntity chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterId));

        ChapterQuizEntity quiz = chapterQuizRepository.findByChapterId(chapterId)
                .orElseGet(() -> new ChapterQuizEntity(chapter));

        int difficultyLevel = resolveDifficultyLevel(chapter);
        int targetQuestionCount = resolveTargetQuestionCount(difficultyLevel);
        List<ParagraphEntity> paragraphs = paragraphRepository.findByChapterIdOrderByParagraphIndex(chapterId);
        ChapterQuizPayload normalized = normalizePayload(payload, paragraphs);
        normalized = ensureMinimumQuestionCount(normalized, paragraphs, difficultyLevel, targetQuestionCount);
        if (normalized.questions().isEmpty()) {
            normalized = buildFallbackPayload(paragraphs, difficultyLevel, targetQuestionCount);
        }

        quiz.setStatus(ChapterQuizStatus.COMPLETED);
        quiz.setGeneratedAt(LocalDateTime.now());
        quiz.setPromptVersion(promptVersion);
        quiz.setModelName(modelName);
        quiz.setPayloadJson(toJson(normalized));
        chapterQuizRepository.save(quiz);
    }

    @Transactional
    public Optional<ChapterQuizGradeResponse> gradeQuiz(String chapterId, List<Integer> selectedOptionIndexes) {
        return gradeQuiz(chapterId, selectedOptionIndexes, null, null);
    }

    @Transactional
    public Optional<ChapterQuizGradeResponse> gradeQuiz(
            String chapterId,
            List<Integer> selectedOptionIndexes,
            String userId) {
        return gradeQuiz(chapterId, selectedOptionIndexes, null, userId);
    }

    @Transactional
    public Optional<ChapterQuizGradeResponse> gradeQuiz(
            String chapterId,
            List<Integer> selectedOptionIndexes,
            String readerId,
            String userId) {
        Optional<ChapterQuizEntity> quizOpt = chapterQuizRepository.findByChapterIdWithChapterAndBook(chapterId);
        if (quizOpt.isEmpty()) {
            return Optional.empty();
        }

        ChapterQuizEntity quiz = quizOpt.get();
        if (quiz.getStatus() != ChapterQuizStatus.COMPLETED) {
            throw new IllegalStateException("Quiz is not ready");
        }

        ChapterQuizPayload payload = toPayload(quiz.getPayloadJson());
        if (payload.questions().isEmpty()) {
            throw new IllegalStateException("Quiz has no questions");
        }

        List<Integer> safeSelections = selectedOptionIndexes == null ? List.of() : selectedOptionIndexes;
        List<ChapterQuizGradeResponse.QuestionResult> results = new ArrayList<>();
        int correctAnswers = 0;

        for (int i = 0; i < payload.questions().size(); i++) {
            ChapterQuizPayload.Question question = payload.questions().get(i);
            int selected = (i < safeSelections.size() && safeSelections.get(i) != null)
                    ? safeSelections.get(i)
                    : -1;
            int correctIndex = question.correctOptionIndex() == null ? 0 : question.correctOptionIndex();
            boolean correct = selected == correctIndex;
            if (correct) {
                correctAnswers++;
            }
            String correctAnswer = (correctIndex >= 0 && correctIndex < question.options().size())
                    ? question.options().get(correctIndex)
                    : "";

            results.add(new ChapterQuizGradeResponse.QuestionResult(
                    i,
                    question.question(),
                    selected,
                    correctIndex,
                    correct,
                    correctAnswer,
                    question.citationParagraphIndex(),
                    question.citationSnippet() == null ? "" : question.citationSnippet()
            ));
        }

        int totalQuestions = payload.questions().size();
        int scorePercent = totalQuestions == 0
                ? 0
                : (int) Math.round((correctAnswers * 100.0) / totalQuestions);
        int difficultyLevel = resolveDifficultyLevel(quiz.getChapter());
        QuizProgressService.ProgressUpdate progressUpdate = quizProgressService.recordAttemptAndEvaluate(
                quiz.getChapter(),
                readerId,
                userId,
                scorePercent,
                correctAnswers,
                totalQuestions,
                difficultyLevel
        );

        return Optional.of(new ChapterQuizGradeResponse(
                quiz.getChapter().getBook().getId(),
                chapterId,
                totalQuestions,
                correctAnswers,
                scorePercent,
                difficultyLevel,
                progressUpdate.newlyUnlocked(),
                progressUpdate.progress(),
                results
        ));
    }

    @Transactional
    public void updateQuizStatus(String chapterId, ChapterQuizStatus status) {
        chapterQuizRepository.findByChapterId(chapterId).ifPresent(quiz -> {
            quiz.setStatus(status);
            chapterQuizRepository.save(quiz);
        });
    }

    private void queueQuizRequest(String chapterId) {
        if (offerIfNotQueued(chapterId)) {
            quizMetricsService.recordGenerationRequested();
            log.debug("Queued chapter quiz generation for chapter: {}", chapterId);
        } else {
            log.debug("Skipped queueing duplicate chapter quiz request: {}", chapterId);
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
                processChapterQuiz(chapterId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing chapter quiz queue", e);
            }
        }
    }

    private void processChapterQuiz(String chapterId) {
        if (cacheOnly) {
            log.info("Skipping queued quiz generation in cache-only mode for chapter {}", chapterId);
            return;
        }
        Optional<ChapterEntity> chapterOpt = chapterRepository.findByIdWithBook(chapterId);
        if (chapterOpt.isEmpty()) {
            log.warn("Cannot generate quiz; chapter not found: {}", chapterId);
            return;
        }

        Optional<ChapterQuizEntity> quizOpt = chapterQuizRepository.findByChapterId(chapterId);
        if (quizOpt.isPresent() && quizOpt.get().getStatus() == ChapterQuizStatus.COMPLETED) {
            log.debug("Skipping quiz generation for chapter {} because quiz is already completed", chapterId);
            return;
        }

        ChapterEntity chapter = chapterOpt.get();
        updateQuizStatus(chapterId, ChapterQuizStatus.GENERATING);
        long startedAtMs = System.currentTimeMillis();
        try {
            List<ParagraphEntity> paragraphs = paragraphRepository.findByChapterIdOrderByParagraphIndex(chapterId);
            QuizGenerationResult result = generateQuizPayload(chapter, paragraphs);
            saveGeneratedQuiz(chapterId, result.payload(), result.promptVersion(), result.modelName());
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAtMs);
            quizMetricsService.recordGenerationCompleted(
                    !"v1-llm-json".equals(result.promptVersion()),
                    durationMs
            );
            log.info("Generated quiz for chapter {} ({})", chapter.getChapterIndex(), chapter.getTitle());
        } catch (Exception e) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAtMs);
            quizMetricsService.recordGenerationFailed(durationMs);
            log.error("Failed to generate quiz for chapter {}", chapterId, e);
            updateQuizStatus(chapterId, ChapterQuizStatus.FAILED);
        }
    }

    private QuizGenerationResult generateQuizPayload(ChapterEntity chapter, List<ParagraphEntity> paragraphs) {
        int difficultyLevel = resolveDifficultyLevel(chapter);
        int targetQuestionCount = resolveTargetQuestionCount(difficultyLevel);

        if (!paragraphs.isEmpty() && reasoningProvider.isAvailable()) {
            try {
                ChapterQuizPayload llmPayload = buildLlmPayload(
                        chapter,
                        paragraphs,
                        difficultyLevel,
                        targetQuestionCount
                );
                llmPayload = ensureMinimumQuestionCount(
                        llmPayload,
                        paragraphs,
                        difficultyLevel,
                        targetQuestionCount
                );
                if (hasQuestions(llmPayload)) {
                    return new QuizGenerationResult(
                            llmPayload,
                            "v1-llm-json",
                            reasoningProvider.getProviderName()
                    );
                }
                log.warn("LLM quiz payload was empty; falling back to extractive quiz for chapter {}", chapter.getId());
            } catch (Exception e) {
                log.warn("LLM quiz generation failed; using extractive fallback for chapter {}", chapter.getId(), e);
            }
        }

        return new QuizGenerationResult(
                buildFallbackPayload(paragraphs, difficultyLevel, targetQuestionCount),
                "v1-extractive",
                "local-extractive"
        );
    }

    private ChapterQuizPayload buildLlmPayload(
            ChapterEntity chapter,
            List<ParagraphEntity> paragraphs,
            int difficultyLevel,
            int targetQuestionCount) {
        String difficultyInstruction = switch (difficultyLevel) {
            case 0 -> "Difficulty 0 (easy): focus on direct facts from a single paragraph.";
            case 1 -> "Difficulty 1 (medium): include factual detail tracking across adjacent moments.";
            case 2 -> "Difficulty 2 (hard): include factual questions requiring connection of details from multiple parts of the chapter.";
            default -> "Difficulty 3+ (expert): keep strictly factual questions but include subtle distractors and multi-detail recall.";
        };
        String prompt = String.format("""
            Generate a spoiler-safe factual multiple-choice quiz for the chapter.

            BOOK: %s by %s
            CHAPTER INDEX: %d
            CHAPTER TITLE: %s

            RULES:
            - Use only chapter context provided below.
            - Ask factual recall questions only (no interpretation/opinion).
            - Target %d questions (%d-%d allowed if context is short).
            - Each question must include 4 options.
            - Exactly one correct answer per question.
            - Provide a citation paragraph index and snippet for each question.
            - %s
            - Return valid JSON only, no markdown.

            CHAPTER CONTEXT:
            %s

            JSON SCHEMA:
            {
              "questions": [
                {
                  "question": "string",
                  "options": ["string", "string", "string", "string"],
                  "correctOptionIndex": 0,
                  "citationParagraphIndex": 12,
                  "citationSnippet": "string"
                }
              ]
            }
            """,
                chapter.getBook().getTitle(),
                chapter.getBook().getAuthor(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                targetQuestionCount,
                Math.max(2, Math.min(targetQuestionCount, 5)),
                maxQuestions,
                difficultyInstruction,
                buildChapterContext(paragraphs)
        );

        String generated = reasoningProvider.generate(prompt, LlmOptions.full(0.2, 0.9, 900));
        String json = extractJsonObject(generated);
        try {
            ChapterQuizPayload payload = objectMapper.readValue(json, ChapterQuizPayload.class);
            return normalizePayload(payload, paragraphs);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON quiz response from LLM provider", e);
        }
    }

    private String buildChapterContext(List<ParagraphEntity> paragraphs) {
        StringBuilder context = new StringBuilder();
        int used = 0;
        for (ParagraphEntity paragraph : paragraphs) {
            String content = paragraph.getContent() == null ? "" : paragraph.getContent().trim();
            if (content.isBlank()) {
                continue;
            }
            String line = "[" + paragraph.getParagraphIndex() + "] " + trimToLength(content, 360) + "\n";
            if (used + line.length() > maxContextChars) {
                break;
            }
            context.append(line);
            used += line.length();
        }

        return context.isEmpty() ? "(empty chapter context)" : context.toString();
    }

    private ChapterQuizPayload buildFallbackPayload(
            List<ParagraphEntity> paragraphs,
            int difficultyLevel,
            int targetQuestionCount) {
        List<SentenceSource> sentenceSources = paragraphs.stream()
                .map(p -> new SentenceSource(
                        p.getParagraphIndex(),
                        p.getContent() == null ? "" : p.getContent().trim(),
                        firstSentence(p.getContent())
                ))
                .filter(source -> !source.sentence().isBlank())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(() -> new LinkedHashSet<>(50)),
                        set -> set.stream().toList()
                ));

        if (sentenceSources.isEmpty()) {
            return EMPTY_PAYLOAD;
        }

        int requested = Math.max(1, targetQuestionCount);
        int maxAllowed = Math.max(1, maxQuestions);
        int questionCount = Math.min(Math.min(requested, maxAllowed), sentenceSources.size());

        List<String> genericDistractors = List.of(
                "This detail does not appear in the chapter.",
                "The narrator skips this event entirely.",
                "The chapter focuses on a different scene."
        );

        List<ChapterQuizPayload.Question> questions = new ArrayList<>();
        for (int i = 0; i < questionCount; i++) {
            SentenceSource source = sentenceSources.get(i);
            String correct = trimToLength(source.sentence(), 180);

            List<String> options = new ArrayList<>();
            options.add(correct);

            for (int j = 1; j < sentenceSources.size() && options.size() < 4; j++) {
                SentenceSource candidate = sentenceSources.get((i + j) % sentenceSources.size());
                String distractor = trimToLength(candidate.sentence(), 180);
                if (!distractor.isBlank() && !distractor.equals(correct)) {
                    options.add(distractor);
                }
            }

            int genericIndex = 0;
            while (options.size() < 4 && genericIndex < genericDistractors.size()) {
                String fallback = genericDistractors.get(genericIndex++);
                if (!options.contains(fallback)) {
                    options.add(fallback);
                }
            }

            List<String> normalizedOptions = options.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .limit(4)
                    .toList();

            if (normalizedOptions.size() < 2) {
                continue;
            }

            int correctOptionIndex = normalizedOptions.indexOf(correct);
            if (correctOptionIndex < 0) {
                correctOptionIndex = 0;
            }

            questions.add(new ChapterQuizPayload.Question(
                    buildFallbackQuestionPrompt(source.paragraphIndex(), difficultyLevel),
                    normalizedOptions,
                    correctOptionIndex,
                    source.paragraphIndex(),
                    trimToLength(source.paragraphText(), 220)
            ));
        }

        return new ChapterQuizPayload(questions);
    }

    private ChapterQuizPayload ensureMinimumQuestionCount(
            ChapterQuizPayload primaryPayload,
            List<ParagraphEntity> paragraphs,
            int difficultyLevel,
            int targetQuestionCount) {
        if (primaryPayload == null || primaryPayload.questions() == null) {
            return buildFallbackPayload(paragraphs, difficultyLevel, targetQuestionCount);
        }
        if (primaryPayload.questions().size() >= targetQuestionCount) {
            return primaryPayload;
        }

        ChapterQuizPayload fallback = buildFallbackPayload(paragraphs, difficultyLevel, targetQuestionCount);
        List<ChapterQuizPayload.Question> merged = new ArrayList<>(primaryPayload.questions());
        for (ChapterQuizPayload.Question question : fallback.questions()) {
            if (merged.size() >= targetQuestionCount) {
                break;
            }
            boolean duplicate = merged.stream()
                    .anyMatch(existing -> Objects.equals(
                            existing.question() == null ? "" : existing.question().trim().toLowerCase(),
                            question.question() == null ? "" : question.question().trim().toLowerCase()
                    ));
            if (!duplicate) {
                merged.add(question);
            }
        }
        return new ChapterQuizPayload(merged);
    }

    private int resolveDifficultyLevel(ChapterEntity chapter) {
        if (!difficultyRampEnabled || chapter == null) {
            return 0;
        }
        int step = Math.max(1, difficultyRampChapterStep);
        int maxLevel = Math.max(0, difficultyRampMaxLevel);
        int chapterIndex = Math.max(0, chapter.getChapterIndex());
        int level = chapterIndex / step;
        return Math.min(level, maxLevel);
    }

    private int resolveTargetQuestionCount(int difficultyLevel) {
        int base = Math.max(1, questionsPerChapter);
        int boost = Math.max(0, questionBoostPerDifficultyLevel) * Math.max(0, difficultyLevel);
        return Math.min(Math.max(1, maxQuestions), base + boost);
    }

    private String buildFallbackQuestionPrompt(int paragraphIndex, int difficultyLevel) {
        if (difficultyLevel <= 0) {
            return "Which statement is directly supported by paragraph " + paragraphIndex + "?";
        }
        if (difficultyLevel == 1) {
            return "Which factual detail from this chapter best matches paragraph " + paragraphIndex + "?";
        }
        return "Which detail is explicitly supported when considering paragraph " + paragraphIndex + " and nearby events?";
    }

    private ChapterQuizResponse toMissingQuizResponse(ChapterEntity chapter) {
        return new ChapterQuizResponse(
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
                resolveDifficultyLevel(chapter),
                EMPTY_VIEW_PAYLOAD
        );
    }

    private ChapterQuizResponse toQuizResponse(ChapterQuizEntity entity) {
        ChapterQuizStatus status = entity.getStatus() != null ? entity.getStatus() : ChapterQuizStatus.PENDING;
        ChapterQuizPayload payload = toPayload(entity.getPayloadJson());
        ChapterEntity chapter = entity.getChapter();

        return new ChapterQuizResponse(
                chapter.getBook().getId(),
                chapter.getId(),
                chapter.getChapterIndex(),
                chapter.getTitle(),
                status.name(),
                status == ChapterQuizStatus.COMPLETED,
                entity.getGeneratedAt(),
                entity.getUpdatedAt(),
                entity.getPromptVersion(),
                entity.getModelName(),
                resolveDifficultyLevel(chapter),
                toViewPayload(payload)
        );
    }

    private ChapterQuizViewPayload toViewPayload(ChapterQuizPayload payload) {
        if (payload == null || payload.questions() == null || payload.questions().isEmpty()) {
            return EMPTY_VIEW_PAYLOAD;
        }

        List<ChapterQuizViewPayload.Question> questions = payload.questions().stream()
                .map(question -> new ChapterQuizViewPayload.Question(question.question(), question.options()))
                .toList();
        return new ChapterQuizViewPayload(questions);
    }

    private ChapterQuizPayload toPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return EMPTY_PAYLOAD;
        }

        try {
            ChapterQuizPayload payload = objectMapper.readValue(payloadJson, ChapterQuizPayload.class);
            return normalizePayload(payload, List.of());
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse quiz payload JSON", e);
            return EMPTY_PAYLOAD;
        }
    }

    private String toJson(ChapterQuizPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize quiz payload", e);
        }
    }

    private ChapterQuizPayload normalizePayload(ChapterQuizPayload payload, List<ParagraphEntity> paragraphs) {
        if (payload == null || payload.questions() == null) {
            return EMPTY_PAYLOAD;
        }

        int maxAllowed = Math.max(1, maxQuestions);
        Map<Integer, String> paragraphByIndex = paragraphs == null
                ? Map.of()
                : paragraphs.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getContent() != null && !p.getContent().isBlank())
                .collect(Collectors.toMap(
                        ParagraphEntity::getParagraphIndex,
                        p -> p.getContent().trim(),
                        (a, b) -> a
                ));

        List<ChapterQuizPayload.Question> normalizedQuestions = payload.questions().stream()
                .filter(Objects::nonNull)
                .limit(maxAllowed)
                .map(question -> normalizeQuestion(question, paragraphByIndex))
                .filter(Objects::nonNull)
                .toList();

        return normalizedQuestions.isEmpty()
                ? EMPTY_PAYLOAD
                : new ChapterQuizPayload(normalizedQuestions);
    }

    private ChapterQuizPayload.Question normalizeQuestion(
            ChapterQuizPayload.Question question,
            Map<Integer, String> paragraphByIndex) {
        String normalizedQuestion = question.question() == null ? "" : question.question().trim();

        List<String> options = question.options() == null
                ? List.of()
                : question.options().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(4)
                .toList();

        if (normalizedQuestion.isBlank() || options.size() < 2) {
            return null;
        }

        int correctOptionIndex = question.correctOptionIndex() == null ? 0 : question.correctOptionIndex();
        if (correctOptionIndex < 0 || correctOptionIndex >= options.size()) {
            correctOptionIndex = 0;
        }

        Integer paragraphIndex = question.citationParagraphIndex();
        if (paragraphIndex != null && !paragraphByIndex.containsKey(paragraphIndex)) {
            paragraphIndex = null;
        }

        String citationSnippet = question.citationSnippet() == null ? "" : question.citationSnippet().trim();
        if (citationSnippet.isBlank() && paragraphIndex != null) {
            citationSnippet = paragraphByIndex.getOrDefault(paragraphIndex, "");
        }

        return new ChapterQuizPayload.Question(
                normalizedQuestion,
                options,
                correctOptionIndex,
                paragraphIndex,
                trimToLength(citationSnippet, 220)
        );
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("No quiz response returned from provider");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        throw new IllegalArgumentException("No JSON object found in quiz response");
    }

    private boolean hasQuestions(ChapterQuizPayload payload) {
        return payload != null && payload.questions() != null && !payload.questions().isEmpty();
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

    private record QuizGenerationResult(
            ChapterQuizPayload payload,
            String promptVersion,
            String modelName
    ) {
    }

    private record SentenceSource(
            int paragraphIndex,
            String paragraphText,
            String sentence
    ) {
    }
}
