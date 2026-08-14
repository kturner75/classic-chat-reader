package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.entity.QuizQuestionOverrideEntity;
import com.classicchatreader.model.ChapterQuizPayload;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.repository.QuizQuestionOverrideRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
@Service
public class TeacherQuizAuthoringService {

    private static final Logger log = LoggerFactory.getLogger(TeacherQuizAuthoringService.class);

    private final ClassroomAuthorizationService authorizationService;
    private final QuizQuestionOverrideRepository overrideRepository;
    private final AssignmentRepository assignmentRepository;
    private final ChapterRepository chapterRepository;
    private final ParagraphRepository paragraphRepository;
    private final ChapterQuizService chapterQuizService;
    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    @Value("${quiz.generation.max-context-chars:7000}")
    private int maxContextChars;

    public TeacherQuizAuthoringService(
            ClassroomAuthorizationService authorizationService,
            QuizQuestionOverrideRepository overrideRepository,
            AssignmentRepository assignmentRepository,
            ChapterRepository chapterRepository,
            ParagraphRepository paragraphRepository,
            ChapterQuizService chapterQuizService,
            @Qualifier("quizReasoningLlmProvider") LlmProvider reasoningProvider,
            ObjectMapper objectMapper,
            EntityManager entityManager) {
        this.authorizationService = authorizationService;
        this.overrideRepository = overrideRepository;
        this.assignmentRepository = assignmentRepository;
        this.chapterRepository = chapterRepository;
        this.paragraphRepository = paragraphRepository;
        this.chapterQuizService = chapterQuizService;
        this.reasoningProvider = reasoningProvider;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Transactional
    public EffectiveQuizResponse getEffectiveQuiz(String userId, String termId, String chapterId) {
        requireTeacher(userId, termId);
        ChapterEntity chapter = requireChapter(chapterId);
        ChapterQuizPayload generated = loadGeneratedPayload(chapterId);
        List<QuizQuestionOverrideEntity> active = overrideRepository
                .findByTermIdAndChapterIdAndStatusAndDeletedAtIsNullOrderBySortOrderAsc(
                        termId, chapterId, QuizQuestionOverrideEntity.STATUS_ACTIVE);
        EffectiveQuizAssembler.MergeResult merged =
                EffectiveQuizAssembler.merge(generated, active, objectMapper);
        return new EffectiveQuizResponse(
                termId,
                chapter.getBook().getId(),
                chapterId,
                chapter.getChapterIndex(),
                chapter.getTitle(),
                toTeacherQuestions(generated),
                active.stream().map(this::toOverrideView).toList(),
                toTeacherQuestions(merged.effective()),
                merged.staleOverrideIds(),
                chapterQuizService.contentVersion(merged.effective())
        );
    }

    @Transactional
    public EffectiveQuizResponse replaceOverrides(
            String userId,
            String termId,
            String chapterId,
            ReplaceOverridesRequest request) {
        requireTeacher(userId, termId);
        ChapterEntity chapter = requireChapter(chapterId);
        // Share content lock with student grade path even when no pass-rule assignment exists.
        chapterQuizService.lockQuizContent(chapterId);
        if (request == null || request.operations() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operations are required.");
        }

        EffectiveQuizResponse previousEffective = getEffectiveQuiz(userId, termId, chapterId);
        String previousContentVersion = previousEffective.contentVersion();
        String expectedVersion = request.expectedContentVersion() == null
                ? null
                : request.expectedContentVersion().trim();
        if (expectedVersion != null && expectedVersion.isEmpty()) {
            expectedVersion = null;
        }
        if (!Objects.equals(previousContentVersion, expectedVersion)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This class quiz was updated since you opened it. Reload the quiz wizard and try again.");
        }

        ChapterQuizPayload generated = loadGeneratedPayload(chapterId);
        var generatedIds = EffectiveQuizAssembler.generatedQuestionIds(generated);

        List<QuizQuestionOverrideEntity> existing = overrideRepository
                .findByTermIdAndChapterIdAndDeletedAtIsNullOrderBySortOrderAsc(termId, chapterId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (QuizQuestionOverrideEntity row : existing) {
            if (QuizQuestionOverrideEntity.STATUS_ACTIVE.equalsIgnoreCase(row.getStatus())) {
                row.setStatus(QuizQuestionOverrideEntity.STATUS_ARCHIVED);
                row.setOverlayKey("archived:" + row.getId());
                row.setUpdatedAt(now);
                overrideRepository.save(row);
            }
        }
        // Flush archived overlay keys before inserting replacements that reuse source IDs.
        entityManager.flush();

        int sort = 0;
        for (OverrideOperation op : request.operations()) {
            if (op == null || op.operation() == null) {
                continue;
            }
            String operation = op.operation().trim().toUpperCase(Locale.ROOT);
            QuizQuestionOverrideEntity row = new QuizQuestionOverrideEntity();
            row.setTermId(termId);
            row.setBookId(chapter.getBook().getId());
            row.setChapterId(chapterId);
            row.setOperation(operation);
            row.setSortOrder(op.sortOrder() != null ? op.sortOrder() : sort);
            row.setStatus(QuizQuestionOverrideEntity.STATUS_ACTIVE);
            row.setCreatedByUserId(userId);
            row.setBasePromptVersion(request.basePromptVersion());
            row.setNotes(trimToNull(op.notes()));

            switch (operation) {
                case QuizQuestionOverrideEntity.OPERATION_DISABLE -> {
                    String sourceId = requireSourceId(op.sourceQuestionId(), generatedIds, "DISABLE");
                    row.setSourceQuestionId(sourceId);
                    row.setOverlayKey(sourceId);
                    row.setQuestionJson(null);
                }
                case QuizQuestionOverrideEntity.OPERATION_OVERRIDE -> {
                    String sourceId = requireSourceId(op.sourceQuestionId(), generatedIds, "OVERRIDE");
                    ChapterQuizPayload.Question question = normalizeTeacherQuestion(op.question(), sourceId);
                    row.setSourceQuestionId(sourceId);
                    row.setOverlayKey(sourceId);
                    row.setQuestionJson(writeQuestion(question));
                }
                case QuizQuestionOverrideEntity.OPERATION_ADD -> {
                    ChapterQuizPayload.Question question = normalizeTeacherQuestion(op.question(), null);
                    row.setSourceQuestionId(null);
                    // overlay_key must be known before insert; use provisional UUID then align with row id
                    String provisionalKey = UUID.randomUUID().toString();
                    row.setOverlayKey(provisionalKey);
                    row.setQuestionJson(writeQuestion(question));
                    row = overrideRepository.save(row);
                    row.setOverlayKey(row.getId());
                    overrideRepository.save(row);
                    sort++;
                    continue;
                }
                default -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Unsupported operation: " + operation);
            }
            overrideRepository.save(row);
            sort++;
        }

        // Replace-set publications always freeze the generated base. OVERRIDE rows still apply
        // after SUPPRESS_BASE (assembler), so kept/edited generated questions survive while
        // hidden base items (e.g. above max-questions) and later generation cannot expand
        // the class quiz without another teacher publication.
        QuizQuestionOverrideEntity suppress = new QuizQuestionOverrideEntity();
        suppress.setTermId(termId);
        suppress.setBookId(chapter.getBook().getId());
        suppress.setChapterId(chapterId);
        suppress.setOperation(QuizQuestionOverrideEntity.OPERATION_SUPPRESS_GENERATED);
        suppress.setSourceQuestionId(null);
        suppress.setOverlayKey(QuizQuestionOverrideEntity.SUPPRESS_OVERLAY_KEY);
        suppress.setSortOrder(-1);
        suppress.setStatus(QuizQuestionOverrideEntity.STATUS_ACTIVE);
        suppress.setCreatedByUserId(userId);
        suppress.setBasePromptVersion(request.basePromptVersion());
        suppress.setNotes("Suppress generated base after teacher replace-set publish");
        overrideRepository.save(suppress);

        EffectiveQuizResponse effective = getEffectiveQuiz(userId, termId, chapterId);
        if (effective.effectiveQuestions() == null || effective.effectiveQuestions().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Effective quiz cannot be empty. Keep at least one question or clear overrides.");
        }
        if (effective.effectiveQuestions().size() > 20) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Effective quiz cannot exceed 20 questions.");
        }
        assertCompatibleWithPublishedPassRules(termId, chapterId, effective);
        String nextContentVersion = effective.contentVersion();
        // Only invalidate attempt windows when the effective quiz content actually changed.
        if (!Objects.equals(previousContentVersion, nextContentVersion)) {
            LocalDateTime activated = LocalDateTime.now(ZoneOffset.UTC);
            for (AssignmentEntity assignment : assignmentRepository
                    .findByContainedChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(chapterId, "PUBLISHED")) {
                if (!termId.equals(assignment.getTermId())) {
                    continue;
                }
                if (!AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(assignment.getQuizSource())) {
                    continue;
                }
                assignment.setQuizRulesActivatedAt(activated);
                assignmentRepository.save(assignment);
            }
        }
        return effective;
    }

    private void assertCompatibleWithPublishedPassRules(
            String termId, String chapterId, EffectiveQuizResponse effective) {
        int questionCount = effective.effectiveQuestions() == null
                ? 0
                : effective.effectiveQuestions().size();
        List<AssignmentEntity> conflicting = assignmentRepository
                .findByContainedChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(chapterId, "PUBLISHED")
                .stream()
                .filter(a -> termId.equals(a.getTermId()))
                .filter(a -> AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(a.getQuizSource()))
                .filter(a -> a.getQuizPassMinCorrect() != null)
                .filter(a -> a.getQuizPassMinCorrect() > questionCount)
                .toList();
        if (conflicting.isEmpty()) {
            return;
        }
        AssignmentEntity worst = conflicting.get(0);
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "This quiz edit would leave " + questionCount + " question(s), but published assignment \""
                        + worst.getTitle() + "\" requires " + worst.getQuizPassMinCorrect()
                        + " correct. Lower that assignment pass threshold (or keep enough questions) before publishing.");
    }

    public List<ChapterQuizPayload.Question> suggestQuestions(
            String userId, String termId, String chapterId, SuggestQuestionsRequest request) {
        // Intentionally not @Transactional: load auth/context in short repo calls, then call AI
        // outside any open DB transaction so provider latency does not hold a connection.
        requireTeacher(userId, termId);
        ChapterEntity chapter = requireChapter(chapterId);
        int count = request != null && request.count() != null ? request.count() : 3;
        count = Math.max(1, Math.min(20, count));
        int optionCount = request != null && request.optionCount() != null ? request.optionCount() : 4;
        optionCount = Math.max(2, Math.min(6, optionCount));
        if (!reasoningProvider.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Quiz AI provider is unavailable.");
        }
        List<ParagraphEntity> paragraphs = paragraphRepository.findByChapterIdOrderByParagraphIndex(chapterId);
        String context = buildChapterContext(paragraphs);
        String bookTitle = chapter.getBook().getTitle();
        String chapterTitle = chapter.getTitle() == null
                ? ("Chapter " + (chapter.getChapterIndex() + 1))
                : chapter.getTitle();
        String prompt = """
                You are helping a teacher author a multiple-choice chapter quiz.
                Book: %s
                Chapter: %s
                Create exactly %d factual, spoiler-safe multiple-choice questions from the chapter text.
                Each question must have exactly %d options and one correctOptionIndex.
                Return JSON only: {"questions":[{"id":"uuid","question":"...","options":["..."],"correctOptionIndex":0,"citationParagraphIndex":0,"citationSnippet":"..."}]}
                
                Chapter text:
                %s
                """.formatted(
                bookTitle,
                chapterTitle,
                count,
                optionCount,
                context
        );
        try {
            String raw = reasoningProvider.generate(prompt, LlmOptions.full(0.2, 0.9, Math.min(4000, 400 + count * optionCount * 40)));
            return parseSuggestedQuestions(raw, count, optionCount);
        } catch (Exception e) {
            log.error(
                    "event=chapter_suggest_questions_failed termId={} chapterId={} errorType={}",
                    termId,
                    chapterId,
                    e.getClass().getSimpleName(),
                    e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to suggest quiz questions.", e);
        }
    }

    public List<String> suggestDistractors(
            String userId, String termId, String chapterId, SuggestDistractorsRequest request) {
        // Intentionally not @Transactional: avoid holding a DB connection across AI latency.
        requireTeacher(userId, termId);
        requireChapter(chapterId);
        if (request == null || isBlank(request.question()) || isBlank(request.correctAnswer())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "question and correctAnswer are required.");
        }
        int count = request.count() != null ? request.count() : 3;
        count = Math.max(1, Math.min(5, count));
        if (!reasoningProvider.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Quiz AI provider is unavailable.");
        }
        List<ParagraphEntity> paragraphs = paragraphRepository.findByChapterIdOrderByParagraphIndex(chapterId);
        String context = buildChapterContext(paragraphs);
        String excludeText = formatExcludedChoices(request.exclude());
        String prompt = """
                Generate exactly %d plausible wrong multiple-choice answers (distractors) for this quiz question.
                Do not repeat the correct answer.%s Return JSON only: {"distractors":["..."]}
                
                Question: %s
                Correct answer: %s
                
                Chapter context:
                %s
                """.formatted(count, excludeText, request.question().trim(), request.correctAnswer().trim(), context);
        try {
            String raw = reasoningProvider.generate(prompt, LlmOptions.full(0.3, 0.9, 500));
            return parseDistractors(raw, request.correctAnswer().trim(), count, request.exclude());
        } catch (Exception e) {
            log.error(
                    "event=chapter_suggest_distractors_failed termId={} chapterId={} errorType={}",
                    termId,
                    chapterId,
                    e.getClass().getSimpleName(),
                    e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to suggest distractors.", e);
        }
    }

    private List<ChapterQuizPayload.Question> parseSuggestedQuestions(String raw, int count, int optionCount)
            throws JsonProcessingException {
        String json = extractJsonObject(raw);
        JsonNode root = objectMapper.readTree(json);
        JsonNode questions = root.get("questions");
        if (questions == null || !questions.isArray()) {
            throw new IllegalArgumentException("Missing questions array");
        }
        List<ChapterQuizPayload.Question> result = new ArrayList<>();
        for (JsonNode node : questions) {
            ChapterQuizPayload.Question parsed = objectMapper.treeToValue(node, ChapterQuizPayload.Question.class);
            ChapterQuizPayload.Question normalized = normalizeTeacherQuestion(parsed, parsed != null ? parsed.id() : null);
            if (normalized != null) {
                result.add(normalized);
            }
            if (result.size() >= count) {
                break;
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No usable questions returned");
        }
        if (result.size() != count) {
            throw new IllegalArgumentException(
                    "Expected exactly " + count + " questions but received " + result.size());
        }
        // Trim options to requested count when longer, preserving order and correct answer.
        return result.stream()
                .map(q -> {
                    List<String> sourceOptions = q.options() == null ? List.of() : q.options().stream()
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .toList();
                    int correct = q.correctOptionIndex() == null ? 0 : q.correctOptionIndex();
                    if (sourceOptions.isEmpty()) {
                        throw new IllegalArgumentException("Question options cannot be empty");
                    }
                    if (correct < 0 || correct >= sourceOptions.size()) {
                        correct = 0;
                    }
                    if (sourceOptions.size() < optionCount) {
                        throw new IllegalArgumentException(
                                "Expected exactly " + optionCount + " options but received "
                                        + sourceOptions.size());
                    }
                    if (sourceOptions.size() == optionCount) {
                        return new ChapterQuizPayload.Question(
                                q.id(), q.question(), sourceOptions, correct,
                                q.citationParagraphIndex(), q.citationSnippet());
                    }
                    List<String> options = new ArrayList<>(sourceOptions.subList(0, optionCount));
                    int remapped = correct;
                    if (correct >= optionCount) {
                        // Keep provider order for the first N-1 options; place correct answer last.
                        options.set(optionCount - 1, sourceOptions.get(correct));
                        remapped = optionCount - 1;
                    }
                    return new ChapterQuizPayload.Question(
                            q.id(), q.question(), options, remapped,
                            q.citationParagraphIndex(), q.citationSnippet());
                })
                .toList();
    }

    private List<String> parseDistractors(String raw, String correctAnswer, int count, List<String> exclude)
            throws JsonProcessingException {
        String json = extractJsonObject(raw);
        JsonNode root = objectMapper.readTree(json);
        JsonNode distractors = root.get("distractors");
        if (distractors == null || !distractors.isArray()) {
            throw new IllegalArgumentException("Missing distractors array");
        }
        List<String> result = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        seen.add(correctAnswer.toLowerCase(java.util.Locale.ROOT));
        addExcludedChoices(seen, exclude);
        for (JsonNode node : distractors) {
            if (node == null || !node.isTextual()) {
                continue;
            }
            String value = node.asText("").trim();
            if (value.isBlank()) {
                continue;
            }
            String key = value.toLowerCase(java.util.Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            result.add(value);
            if (result.size() >= count) {
                break;
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No usable distractors returned");
        }
        if (result.size() != count) {
            throw new IllegalArgumentException(
                    "Expected exactly " + count + " distractors but received " + result.size());
        }
        return result;
    }

    private ChapterQuizPayload.Question normalizeTeacherQuestion(
            ChapterQuizPayload.Question question, String preferredId) {
        if (question == null || isBlank(question.question()) || question.options() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each question needs a stem and options.");
        }
        List<String> sourceOptions = question.options();
        int correct = question.correctOptionIndex() == null ? 0 : question.correctOptionIndex();
        if (correct < 0 || correct >= sourceOptions.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "correctOptionIndex is out of range.");
        }
        String correctRaw = sourceOptions.get(correct);
        if (correctRaw == null || correctRaw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "correct option text cannot be blank.");
        }
        String correctText = correctRaw.trim();
        List<String> cleaned = sourceOptions.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (cleaned.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each question needs at least 2 options.");
        }
        // Cap at 6 while always retaining the correct answer text.
        List<String> rawOptions = new ArrayList<>();
        if (cleaned.size() <= 6) {
            rawOptions.addAll(cleaned);
        } else {
            for (String option : cleaned) {
                if (rawOptions.size() >= 5) {
                    break;
                }
                if (!option.equalsIgnoreCase(correctText)) {
                    rawOptions.add(option);
                }
            }
            rawOptions.add(correctText);
        }
        List<String> options = new ArrayList<>();
        for (String option : rawOptions) {
            if (options.stream().noneMatch(existing -> existing.equalsIgnoreCase(option))) {
                options.add(option);
            }
        }
        if (options.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each question needs at least 2 unique options.");
        }
        int remappedCorrect = -1;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(correctText)) {
                remappedCorrect = i;
                break;
            }
        }
        if (remappedCorrect < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "correctOptionIndex no longer matches after removing duplicate options.");
        }
        String id = !isBlank(preferredId)
                ? preferredId.trim()
                : (!isBlank(question.id()) ? question.id().trim() : UUID.randomUUID().toString());
        return new ChapterQuizPayload.Question(
                id,
                question.question().trim(),
                options,
                remappedCorrect,
                question.citationParagraphIndex(),
                question.citationSnippet() == null ? "" : question.citationSnippet().trim()
        );
    }

    private String requireSourceId(String sourceQuestionId, java.util.Set<String> generatedIds, String op) {
        String sourceId = trimToNull(sourceQuestionId);
        if (sourceId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, op + " requires sourceQuestionId.");
        }
        if (!generatedIds.isEmpty() && !generatedIds.contains(sourceId)) {
            // Allow teacher DISABLE/OVERRIDE of previously known ids even if base missing after regen;
            // assembler will mark stale. Still accept write.
            return sourceId;
        }
        return sourceId;
    }

    private ChapterQuizPayload loadGeneratedPayload(String chapterId) {
        // Persist lazy id backfill so OVERRIDE/DISABLE rows reference durable source ids.
        return chapterQuizService.loadCompletedPayloadWithIdBackfill(chapterId);
    }

    private ChapterEntity requireChapter(String chapterId) {
        return chapterRepository.findByIdWithBook(chapterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found."));
    }

    private void requireTeacher(String userId, String termId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account sign-in required.");
        }
        if (!authorizationService.canManageTerm(userId, termId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher access required.");
        }
    }

    private OverrideView toOverrideView(QuizQuestionOverrideEntity row) {
        ChapterQuizPayload.Question question = null;
        if (row.getQuestionJson() != null && !row.getQuestionJson().isBlank()) {
            try {
                question = objectMapper.readValue(row.getQuestionJson(), ChapterQuizPayload.Question.class);
            } catch (JsonProcessingException ignored) {
                question = null;
            }
        }
        return new OverrideView(
                row.getId(),
                row.getOperation(),
                row.getSourceQuestionId(),
                row.getSortOrder(),
                question,
                row.getNotes()
        );
    }

    private List<TeacherQuestionView> toTeacherQuestions(ChapterQuizPayload payload) {
        if (payload == null || payload.questions() == null) {
            return List.of();
        }
        return payload.questions().stream()
                .filter(Objects::nonNull)
                .map(q -> new TeacherQuestionView(
                        q.id(),
                        q.question(),
                        q.options(),
                        q.correctOptionIndex(),
                        q.citationParagraphIndex(),
                        q.citationSnippet()))
                .toList();
    }

    private String writeQuestion(ChapterQuizPayload.Question question) {
        try {
            return objectMapper.writeValueAsString(question);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize question", e);
        }
    }

    private String buildChapterContext(List<ParagraphEntity> paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (ParagraphEntity paragraph : paragraphs) {
            if (paragraph == null || paragraph.getContent() == null || paragraph.getContent().isBlank()) {
                continue;
            }
            String line = "[" + paragraph.getParagraphIndex() + "] " + paragraph.getContent().trim();
            if (sb.length() + line.length() + 1 > maxContextChars) {
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Empty AI response");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        throw new IllegalArgumentException("No JSON object found");
    }

    static final int MAX_EXCLUDED_CHOICES = 20;
    static final int MAX_EXCLUDED_CHOICE_LENGTH = 200;

    static List<String> sanitizeExcludedChoices(List<String> exclude) {
        if (exclude == null || exclude.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        for (String value : exclude) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.length() > MAX_EXCLUDED_CHOICE_LENGTH) {
                trimmed = trimmed.substring(0, MAX_EXCLUDED_CHOICE_LENGTH);
            }
            unique.add(trimmed);
            if (unique.size() >= MAX_EXCLUDED_CHOICES) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    static String formatExcludedChoices(List<String> exclude) {
        List<String> sanitized = sanitizeExcludedChoices(exclude);
        if (sanitized.isEmpty()) {
            return "";
        }
        return " Do not reuse these existing choices: " + String.join("; ", sanitized) + ".";
    }

    static void addExcludedChoices(java.util.Set<String> seen, List<String> exclude) {
        if (seen == null) {
            return;
        }
        for (String value : sanitizeExcludedChoices(exclude)) {
            seen.add(value.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ChapterQuizPayload toPayload(List<TeacherQuestionView> questions) {
        if (questions == null || questions.isEmpty()) {
            return new ChapterQuizPayload(List.of());
        }
        List<ChapterQuizPayload.Question> mapped = questions.stream()
                .filter(Objects::nonNull)
                .map(q -> new ChapterQuizPayload.Question(
                        q.id(),
                        q.question(),
                        q.options(),
                        q.correctOptionIndex(),
                        q.citationParagraphIndex(),
                        q.citationSnippet() == null ? "" : q.citationSnippet()))
                .toList();
        return new ChapterQuizPayload(mapped);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record EffectiveQuizResponse(
            String termId,
            String bookId,
            String chapterId,
            Integer chapterIndex,
            String chapterTitle,
            List<TeacherQuestionView> generatedQuestions,
            List<OverrideView> overrides,
            List<TeacherQuestionView> effectiveQuestions,
            List<String> staleOverrideIds,
            String contentVersion
    ) {
    }

    public record OverrideView(
            String id,
            String operation,
            String sourceQuestionId,
            int sortOrder,
            ChapterQuizPayload.Question question,
            String notes
    ) {
    }

    public record TeacherQuestionView(
            String id,
            String question,
            List<String> options,
            Integer correctOptionIndex,
            Integer citationParagraphIndex,
            String citationSnippet
    ) {
    }

    public record ReplaceOverridesRequest(
            String basePromptVersion,
            String expectedContentVersion,
            List<OverrideOperation> operations
    ) {
    }

    public record OverrideOperation(
            String operation,
            String sourceQuestionId,
            Integer sortOrder,
            ChapterQuizPayload.Question question,
            String notes
    ) {
    }

    public record SuggestQuestionsRequest(Integer count, Integer optionCount, String bookId, List<String> chapterIds) {
        public SuggestQuestionsRequest(Integer count, Integer optionCount) {
            this(count, optionCount, null, null);
        }
    }

    public record SuggestDistractorsRequest(
            String question, String correctAnswer, Integer count, String bookId, List<String> chapterIds,
            List<String> exclude) {
        public SuggestDistractorsRequest(String question, String correctAnswer, Integer count) {
            this(question, correctAnswer, count, null, null, null);
        }
    }
}
