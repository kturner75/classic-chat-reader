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

    private final ClassroomAuthorizationService authorizationService;
    private final QuizQuestionOverrideRepository overrideRepository;
    private final AssignmentRepository assignmentRepository;
    private final ChapterRepository chapterRepository;
    private final ParagraphRepository paragraphRepository;
    private final ChapterQuizService chapterQuizService;
    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper;

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
            ObjectMapper objectMapper) {
        this.authorizationService = authorizationService;
        this.overrideRepository = overrideRepository;
        this.assignmentRepository = assignmentRepository;
        this.chapterRepository = chapterRepository;
        this.paragraphRepository = paragraphRepository;
        this.chapterQuizService = chapterQuizService;
        this.reasoningProvider = reasoningProvider;
        this.objectMapper = objectMapper;
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
                merged.staleOverrideIds()
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
        if (request == null || request.operations() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operations are required.");
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

        // If this publication does not keep any generated source via OVERRIDE, suppress the base
        // so a later background generation cannot silently expand the class quiz.
        boolean keepsGenerated = request.operations().stream()
                .filter(Objects::nonNull)
                .anyMatch(op -> QuizQuestionOverrideEntity.OPERATION_OVERRIDE
                        .equalsIgnoreCase(op.operation() == null ? "" : op.operation().trim()));
        if (!keepsGenerated) {
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
        }

        EffectiveQuizResponse effective = getEffectiveQuiz(userId, termId, chapterId);
        if (effective.effectiveQuestions() == null || effective.effectiveQuestions().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Effective quiz cannot be empty. Keep at least one question or clear overrides.");
        }
        assertCompatibleWithPublishedPassRules(termId, chapterId, effective);
        return effective;
    }

    private void assertCompatibleWithPublishedPassRules(
            String termId, String chapterId, EffectiveQuizResponse effective) {
        int questionCount = effective.effectiveQuestions() == null
                ? 0
                : effective.effectiveQuestions().size();
        List<AssignmentEntity> conflicting = assignmentRepository
                .findByChapterIdAndQuizRequiredTrueAndStatusAndDeletedAtIsNull(chapterId, "PUBLISHED")
                .stream()
                .filter(a -> termId.equals(a.getTermId()))
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

    @Transactional(readOnly = true)
    public List<ChapterQuizPayload.Question> suggestQuestions(
            String userId, String termId, String chapterId, SuggestQuestionsRequest request) {
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
                chapter.getBook().getTitle(),
                chapter.getTitle() == null ? ("Chapter " + (chapter.getChapterIndex() + 1)) : chapter.getTitle(),
                count,
                optionCount,
                context
        );
        try {
            String raw = reasoningProvider.generate(prompt, LlmOptions.full(0.2, 0.9, Math.min(4000, 400 + count * optionCount * 40)));
            return parseSuggestedQuestions(raw, optionCount);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Failed to suggest quiz questions: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<String> suggestDistractors(
            String userId, String termId, String chapterId, SuggestDistractorsRequest request) {
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
        String prompt = """
                Generate exactly %d plausible wrong multiple-choice answers (distractors) for this quiz question.
                Do not repeat the correct answer. Return JSON only: {"distractors":["..."]}
                
                Question: %s
                Correct answer: %s
                
                Chapter context:
                %s
                """.formatted(count, request.question().trim(), request.correctAnswer().trim(), context);
        try {
            String raw = reasoningProvider.generate(prompt, LlmOptions.full(0.3, 0.9, 500));
            return parseDistractors(raw, request.correctAnswer().trim(), count);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Failed to suggest distractors: " + e.getMessage());
        }
    }

    private List<ChapterQuizPayload.Question> parseSuggestedQuestions(String raw, int optionCount)
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
            result.add(normalizeTeacherQuestion(parsed, parsed != null ? parsed.id() : null));
            if (result.size() >= 20) {
                break;
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No usable questions returned");
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
                        return q;
                    }
                    if (correct < 0 || correct >= sourceOptions.size()) {
                        correct = 0;
                    }
                    if (sourceOptions.size() <= optionCount) {
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

    private List<String> parseDistractors(String raw, String correctAnswer, int count)
            throws JsonProcessingException {
        String json = extractJsonObject(raw);
        JsonNode root = objectMapper.readTree(json);
        JsonNode distractors = root.get("distractors");
        if (distractors == null || !distractors.isArray()) {
            throw new IllegalArgumentException("Missing distractors array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode node : distractors) {
            if (node == null || !node.isTextual()) {
                continue;
            }
            String value = node.asText("").trim();
            if (value.isBlank() || value.equalsIgnoreCase(correctAnswer) || result.contains(value)) {
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
        return result;
    }

    private ChapterQuizPayload.Question normalizeTeacherQuestion(
            ChapterQuizPayload.Question question, String preferredId) {
        if (question == null || isBlank(question.question()) || question.options() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each question needs a stem and options.");
        }
        List<String> rawOptions = question.options().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(6)
                .toList();
        if (rawOptions.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each question needs at least 2 options.");
        }
        int correct = question.correctOptionIndex() == null ? 0 : question.correctOptionIndex();
        if (correct < 0 || correct >= rawOptions.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "correctOptionIndex is out of range.");
        }
        String correctText = rawOptions.get(correct);
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
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
            List<String> staleOverrideIds
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

    public record SuggestQuestionsRequest(Integer count, Integer optionCount) {
    }

    public record SuggestDistractorsRequest(String question, String correctAnswer, Integer count) {
    }
}
