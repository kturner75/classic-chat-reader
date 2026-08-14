package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentChapterEntity;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.AssignmentQuizEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.entity.UserReaderStateEntity;
import com.classicchatreader.model.AccountStateSnapshot;
import com.classicchatreader.model.ChapterQuizGradeResponse;
import com.classicchatreader.model.ChapterQuizPayload;
import com.classicchatreader.model.ChapterQuizViewPayload;
import com.classicchatreader.repository.AssignmentQuizRepository;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.repository.UserReaderStateRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AssignmentQuizService {

    private final ClassroomAuthorizationService authorizationService;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentQuizRepository assignmentQuizRepository;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final ParagraphRepository paragraphRepository;
    private final ChapterQuizService chapterQuizService;
    private final ClassroomEffectiveQuizService classroomEffectiveQuizService;
    private final ClassroomQuizPolicyService classroomQuizPolicyService;
    private final QuizProgressService quizProgressService;
    private final ClassroomProperties classroomProperties;
    private final UserReaderStateRepository userReaderStateRepository;
    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${quiz.generation.max-context-chars:7000}")
    private int maxContextChars;

    public AssignmentQuizService(
            ClassroomAuthorizationService authorizationService,
            AssignmentRepository assignmentRepository,
            AssignmentQuizRepository assignmentQuizRepository,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            ParagraphRepository paragraphRepository,
            ChapterQuizService chapterQuizService,
            ClassroomEffectiveQuizService classroomEffectiveQuizService,
            ClassroomQuizPolicyService classroomQuizPolicyService,
            QuizProgressService quizProgressService,
            ClassroomProperties classroomProperties,
            UserReaderStateRepository userReaderStateRepository,
            @Qualifier("quizReasoningLlmProvider") LlmProvider reasoningProvider,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.authorizationService = authorizationService;
        this.assignmentRepository = assignmentRepository;
        this.assignmentQuizRepository = assignmentQuizRepository;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.paragraphRepository = paragraphRepository;
        this.chapterQuizService = chapterQuizService;
        this.classroomEffectiveQuizService = classroomEffectiveQuizService;
        this.classroomQuizPolicyService = classroomQuizPolicyService;
        this.quizProgressService = quizProgressService;
        this.classroomProperties = classroomProperties;
        this.userReaderStateRepository = userReaderStateRepository;
        this.reasoningProvider = reasoningProvider;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public AssignmentEffectiveQuizResponse getEffectiveQuiz(String userId, String assignmentId) {
        AssignmentEntity assignment = requireTeacherAssignment(userId, assignmentId);
        boolean chapterDefaultAvailable = isChapterDefaultAvailable(assignment);
        ChapterQuizPayload payload = resolvePayload(assignment).orElse(new ChapterQuizPayload(List.of()));
        String source = assignment.getQuizSource();
        if (source == null && assignment.isQuizRequired()) {
            source = chapterDefaultAvailable
                    ? AssignmentEntity.QUIZ_SOURCE_CHAPTER
                    : AssignmentEntity.QUIZ_SOURCE_CUSTOM;
        }
        return new AssignmentEffectiveQuizResponse(
                assignment.getId(),
                assignment.getTermId(),
                assignment.getBookId(),
                source,
                chapterDefaultAvailable,
                toTeacherQuestions(payload),
                chapterQuizService.contentVersion(payload)
        );
    }

    @Transactional
    public AssignmentEffectiveQuizResponse saveCustomQuiz(
            String userId, String assignmentId, SaveAssignmentQuizRequest request) {
        AssignmentEntity assignment = requireTeacherAssignment(userId, assignmentId);
        lockAssignmentQuizExclusive(assignment);
        if (request == null || request.questions() == null || request.questions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one question is required.");
        }
        if (request.questions().size() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignment quiz cannot exceed 20 questions.");
        }
        List<ChapterQuizPayload.Question> normalized = new ArrayList<>();
        for (ChapterQuizPayload.Question question : request.questions()) {
            ChapterQuizPayload.Question next = normalizeQuestion(question);
            if (next != null) {
                normalized.add(next);
            }
        }
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one question is required.");
        }
        if (assignment.getQuizPassMinCorrect() != null
                && assignment.getQuizPassMinCorrect() > normalized.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "quizPassMinCorrect (" + assignment.getQuizPassMinCorrect()
                            + ") cannot exceed the quiz size (" + normalized.size() + " questions).");
        }
        ChapterQuizPayload payload = new ChapterQuizPayload(normalized);
        String nextVersion = chapterQuizService.contentVersion(payload);
        String previousVersion = existingCustomContentVersion(assignmentId);
        if (previousVersion == null && AssignmentEntity.QUIZ_SOURCE_CHAPTER.equalsIgnoreCase(assignment.getQuizSource())) {
            previousVersion = resolvePayload(assignment)
                    .map(chapterQuizService::contentVersion)
                    .orElse(null);
        }
        AssignmentQuizEntity row = assignmentQuizRepository.findByAssignmentId(assignmentId)
                .orElseGet(AssignmentQuizEntity::new);
        row.setAssignmentId(assignmentId);
        row.setPayloadJson(chapterQuizService.serializePayload(payload));
        row.setCreatedByUserId(userId);
        assignmentQuizRepository.save(row);
        boolean alreadyCustom = AssignmentEntity.QUIZ_SOURCE_CUSTOM.equalsIgnoreCase(assignment.getQuizSource());
        boolean published = "PUBLISHED".equalsIgnoreCase(assignment.getStatus());
        if (alreadyCustom || !published) {
            assignment.setQuizRequired(true);
            assignment.setQuizSource(AssignmentEntity.QUIZ_SOURCE_CUSTOM);
            if (!java.util.Objects.equals(previousVersion, nextVersion)) {
                assignment.setQuizRulesActivatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            }
            assignmentRepository.save(assignment);
        }
        return getEffectiveQuiz(userId, assignmentId);
    }

    private String existingCustomContentVersion(String assignmentId) {
        return assignmentQuizRepository.findByAssignmentId(assignmentId)
                .map(row -> parsePayload(row.getPayloadJson()))
                .map(chapterQuizService::contentVersion)
                .orElse(null);
    }

    public List<ChapterQuizPayload.Question> suggestQuestions(
            String userId, String assignmentId, TeacherQuizAuthoringService.SuggestQuestionsRequest request) {
        AssignmentEntity assignment = requireTeacherAssignment(userId, assignmentId);
        int count = request != null && request.count() != null ? request.count() : 3;
        count = Math.max(1, Math.min(20, count));
        int optionCount = request != null && request.optionCount() != null ? request.optionCount() : 4;
        optionCount = Math.max(2, Math.min(6, optionCount));
        if (!reasoningProvider.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Quiz AI provider is unavailable.");
        }
        BookEntity book = bookRepository.findById(assignment.getBookId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown bookId."));
        String context = buildAssignmentContext(assignment);
        String scope = assignment.isWholeBook()
                ? "the whole book"
                : assignment.getChapters().size() + " selected chapter(s)";
        String prompt = """
                You are helping a teacher author a multiple-choice assignment quiz.
                Book: %s
                Scope: %s
                Create exactly %d factual, spoiler-safe multiple-choice questions from the assigned text.
                Each question must have exactly %d options and one correctOptionIndex.
                Return JSON only: {"questions":[{"id":"uuid","question":"...","options":["..."],"correctOptionIndex":0,"citationParagraphIndex":0,"citationSnippet":"..."}]}

                Assigned text:
                %s
                """.formatted(book.getTitle(), scope, count, optionCount, context);
        try {
            String raw = reasoningProvider.generate(
                    prompt, LlmOptions.full(0.2, 0.9, Math.min(4000, 400 + count * optionCount * 40)));
            return parseSuggestedQuestions(raw, count, optionCount);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Failed to suggest quiz questions: " + e.getMessage());
        }
    }

    public List<String> suggestDistractors(
            String userId,
            String assignmentId,
            TeacherQuizAuthoringService.SuggestDistractorsRequest request) {
        AssignmentEntity assignment = requireTeacherAssignment(userId, assignmentId);
        if (request == null || isBlank(request.question()) || isBlank(request.correctAnswer())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "question and correctAnswer are required.");
        }
        int count = request.count() != null ? request.count() : 3;
        count = Math.max(1, Math.min(5, count));
        if (!reasoningProvider.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Quiz AI provider is unavailable.");
        }
        String context = buildAssignmentContext(assignment);
        String prompt = """
                Generate exactly %d plausible wrong multiple-choice answers (distractors) for this quiz question.
                Do not repeat the correct answer. Return JSON only: {"distractors":["..."]}

                Question: %s
                Correct answer: %s

                Assigned text:
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

    @Transactional(readOnly = true)
    public Optional<AssignmentQuizViewResponse> getStudentQuiz(String userId, String assignmentId) {
        AssignmentEntity assignment = requireStudentAssignment(userId, assignmentId);
        if (!authorizationService.canManageTerm(userId, assignment.getTermId())) {
            assertReadingComplete(userId, assignment);
        }
        if (!assignment.isQuizRequired()) {
            return Optional.empty();
        }
        ChapterQuizPayload payload = resolvePayload(assignment).orElse(null);
        if (payload == null || payload.questions() == null || payload.questions().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AssignmentQuizViewResponse(
                assignment.getId(),
                assignment.getBookId(),
                assignment.getQuizSource(),
                true,
                chapterQuizService.toStudentView(payload)
        ));
    }

    public Optional<ChapterQuizGradeResponse> gradeStudentQuiz(
            String userId,
            String assignmentId,
            List<Integer> selectedOptionIndexes,
            List<String> questionIds,
            String contentVersion,
            String readerId) {
        String identityKey = (userId != null && !userId.isBlank()) ? userId : (readerId == null ? "" : readerId);
        String lockKey = (identityKey + "\u0000" + (assignmentId == null ? "" : assignmentId)).intern();
        synchronized (lockKey) {
            return transactionTemplate.execute(status ->
                    gradeStudentQuizInTx(userId, assignmentId, selectedOptionIndexes, questionIds, contentVersion, readerId));
        }
    }

    private Optional<ChapterQuizGradeResponse> gradeStudentQuizInTx(
            String userId,
            String assignmentId,
            List<Integer> selectedOptionIndexes,
            List<String> questionIds,
            String contentVersion,
            String readerId) {
        AssignmentEntity assignment = requireStudentAssignment(userId, assignmentId);
        if (!authorizationService.canManageTerm(userId, assignment.getTermId())) {
            assertReadingComplete(userId, assignment);
        }
        if (!assignment.isQuizRequired()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This assignment does not require a quiz.");
        }
        lockAssignmentQuizShared(assignment);
        classroomQuizPolicyService.assertCanAttemptAssignment(assignmentId, userId);
        ChapterQuizPayload payload = resolvePayload(assignment).orElse(null);
        if (payload == null || payload.questions() == null || payload.questions().isEmpty()) {
            return Optional.empty();
        }
        assertSubmissionMatches(payload, questionIds, contentVersion);
        assertSubmissionComplete(payload, selectedOptionIndexes);

        int total = payload.questions().size();
        int correct = 0;
        List<ChapterQuizGradeResponse.QuestionResult> results = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            ChapterQuizPayload.Question question = payload.questions().get(i);
            int selected = selectedOptionIndexes.get(i);
            int expected = question.correctOptionIndex() == null ? 0 : question.correctOptionIndex();
            boolean isCorrect = selected == expected;
            if (isCorrect) {
                correct++;
            }
            String correctAnswer = question.options() != null && expected >= 0 && expected < question.options().size()
                    ? question.options().get(expected)
                    : "";
            results.add(new ChapterQuizGradeResponse.QuestionResult(
                    i,
                    question.question(),
                    selected,
                    expected,
                    isCorrect,
                    correctAnswer,
                    question.citationParagraphIndex(),
                    question.citationSnippet()
            ));
        }
        int scorePercent = total == 0 ? 0 : (int) Math.round((correct * 100.0) / total);
        ChapterEntity chapter = null;
        String chapterId = assignment.singleChapterId();
        if (chapterId != null) {
            chapter = chapterRepository.findByIdWithBook(chapterId).orElse(null);
        }
        QuizProgressService.ProgressUpdate progress = quizProgressService.recordAttemptAndEvaluate(
                chapter,
                assignment.getBookId(),
                assignment.getId(),
                readerId,
                userId,
                scorePercent,
                correct,
                total,
                0
        );
        return Optional.of(new ChapterQuizGradeResponse(
                assignment.getBookId(),
                chapterId,
                total,
                correct,
                scorePercent,
                0,
                progress.newlyUnlocked(),
                progress.progress(),
                results
        ));
    }

    public boolean isChapterDefaultAvailable(AssignmentEntity assignment) {
        String chapterId = assignment.singleChapterId();
        if (chapterId == null) {
            return false;
        }
        Optional<Integer> count = classroomEffectiveQuizService.resolveEffectiveQuestionCount(
                assignment.getTermId(), chapterId);
        return count.isPresent() && count.get() > 0;
    }

    Optional<ChapterQuizPayload> resolvePayload(AssignmentEntity assignment) {
        if (AssignmentEntity.QUIZ_SOURCE_CUSTOM.equalsIgnoreCase(assignment.getQuizSource())) {
            return assignmentQuizRepository.findByAssignmentId(assignment.getId())
                    .map(row -> parsePayload(row.getPayloadJson()))
                    .filter(payload -> payload.questions() != null && !payload.questions().isEmpty());
        }
        String chapterId = assignment.singleChapterId();
        if (chapterId == null) {
            return Optional.empty();
        }
        return classroomEffectiveQuizService.loadEffectivePayloadForTerm(assignment.getTermId(), chapterId);
    }

    private ChapterQuizPayload parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return new ChapterQuizPayload(List.of());
        }
        try {
            return objectMapper.readValue(json, ChapterQuizPayload.class);
        } catch (Exception e) {
            return new ChapterQuizPayload(List.of());
        }
    }

    private void lockAssignmentQuizExclusive(AssignmentEntity assignment) {
        assignmentQuizRepository.findByAssignmentIdForUpdate(assignment.getId());
        String chapterId = assignment.singleChapterId();
        if (chapterId != null) {
            chapterQuizService.lockQuizContent(chapterId);
        }
    }

    private void lockAssignmentQuizShared(AssignmentEntity assignment) {
        assignmentQuizRepository.findByAssignmentIdForShare(assignment.getId());
        String chapterId = assignment.singleChapterId();
        if (chapterId != null) {
            chapterQuizService.lockQuizContentShared(chapterId);
        }
    }

    private void assertReadingComplete(String userId, AssignmentEntity assignment) {
        if (!hasCompletedAssignedReading(userId, assignment)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Finish the assigned reading before taking this quiz.");
        }
    }

    private boolean hasCompletedAssignedReading(String userId, AssignmentEntity assignment) {
        AccountStateSnapshot.BookActivity activity = loadBookActivity(userId, assignment.getBookId());
        if (activity == null) {
            return false;
        }
        if (Boolean.TRUE.equals(activity.completed())
                || activity.completedAt() != null
                || Math.max(
                activity.maxProgressRatio() == null ? 0d : activity.maxProgressRatio(),
                activity.progressRatio() == null ? 0d : activity.progressRatio()) >= 0.999) {
            return true;
        }
        if (assignment.isWholeBook() || assignment.getChapters() == null || assignment.getChapters().isEmpty()) {
            return false;
        }
        int targetIndex = assignment.getChapters().stream()
                .mapToInt(AssignmentChapterEntity::getChapterIndex)
                .max()
                .orElse(-1);
        Integer reached = maxReachedChapterIndex(activity);
        return reached != null && targetIndex >= 0 && reached >= targetIndex;
    }

    private static Integer maxReachedChapterIndex(AccountStateSnapshot.BookActivity activity) {
        int chapterCount = activity.chapterCount() != null && activity.chapterCount() > 0
                ? activity.chapterCount()
                : 1;
        double maxProgress = Math.max(0d, Math.min(1d,
                activity.maxProgressRatio() == null ? 0d : activity.maxProgressRatio()));
        int fromProgress = -1;
        if (maxProgress > 0d) {
            fromProgress = Math.min(chapterCount - 1, Math.max(0, (int) Math.ceil(maxProgress * chapterCount - 1e-9) - 1));
        }
        int last = activity.lastChapterIndex() == null ? -1 : activity.lastChapterIndex();
        int reached = Math.max(fromProgress, last);
        return reached >= 0 ? reached : null;
    }

    private AccountStateSnapshot.BookActivity loadBookActivity(String userId, String bookId) {
        if (userId == null || userId.isBlank() || bookId == null || bookId.isBlank()) {
            return null;
        }
        return userReaderStateRepository.findById(userId)
                .map(UserReaderStateEntity::getStateJson)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, AccountStateSnapshot.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .map(AccountStateSnapshot::bookActivity)
                .map(map -> map == null ? null : map.get(bookId))
                .orElse(null);
    }

    private AssignmentEntity requireTeacherAssignment(String userId, String assignmentId) {
        AssignmentEntity assignment = assignmentRepository.findByIdAndDeletedAtIsNull(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found."));
        if (!authorizationService.canManageTerm(userId, assignment.getTermId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
        }
        return assignment;
    }

    private AssignmentEntity requireStudentAssignment(String userId, String assignmentId) {
        AssignmentEntity assignment = assignmentRepository.findByIdAndDeletedAtIsNull(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found."));
        if (!"PUBLISHED".equals(assignment.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
        }
        boolean teacher = authorizationService.canManageTerm(userId, assignment.getTermId());
        if (!teacher && !authorizationService.isActiveStudentOnTerm(userId, assignment.getTermId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
        }
        if (!teacher
                && assignment.getAvailableFromDate() != null
                && assignment.getAvailableFromDate().isAfter(classroomProperties.today())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
        }
        return assignment;
    }

    private String buildAssignmentContext(AssignmentEntity assignment) {
        List<ChapterEntity> chapters;
        if (assignment.isWholeBook()) {
            chapters = chapterRepository.findByBookIdOrderByChapterIndex(assignment.getBookId());
        } else {
            chapters = assignment.getChapters().stream()
                    .map(AssignmentChapterEntity::getChapterId)
                    .map(id -> chapterRepository.findById(id).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
        }
        StringBuilder sb = new StringBuilder();
        for (ChapterEntity chapter : chapters) {
            String title = chapter.getTitle() == null
                    ? ("Chapter " + (chapter.getChapterIndex() + 1))
                    : chapter.getTitle();
            String heading = "## " + title;
            if (sb.length() + heading.length() + 1 > maxContextChars) {
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(heading);
            List<ParagraphEntity> paragraphs =
                    paragraphRepository.findByChapterIdOrderByParagraphIndex(chapter.getId());
            for (ParagraphEntity paragraph : paragraphs) {
                if (paragraph == null || paragraph.getContent() == null || paragraph.getContent().isBlank()) {
                    continue;
                }
                String line = "[" + paragraph.getParagraphIndex() + "] " + paragraph.getContent().trim();
                if (sb.length() + line.length() + 1 > maxContextChars) {
                    return sb.toString();
                }
                sb.append('\n').append(line);
            }
        }
        return sb.toString();
    }

    private List<TeacherQuizAuthoringService.TeacherQuestionView> toTeacherQuestions(ChapterQuizPayload payload) {
        if (payload == null || payload.questions() == null) {
            return List.of();
        }
        return payload.questions().stream()
                .filter(Objects::nonNull)
                .map(q -> new TeacherQuizAuthoringService.TeacherQuestionView(
                        q.id(), q.question(), q.options(), q.correctOptionIndex(),
                        q.citationParagraphIndex(), q.citationSnippet()))
                .toList();
    }

    private ChapterQuizPayload.Question normalizeQuestion(ChapterQuizPayload.Question question) {
        if (question == null || question.question() == null || question.question().isBlank()) {
            return null;
        }
        List<String> rawOptions = question.options() == null ? List.of() : question.options();
        int requestedCorrect = question.correctOptionIndex() == null ? 0 : question.correctOptionIndex();
        String correctAnswer = requestedCorrect >= 0 && requestedCorrect < rawOptions.size()
                ? rawOptions.get(requestedCorrect)
                : null;
        List<String> options = rawOptions.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (options.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each question needs at least two options.");
        }
        int correct = correctAnswer == null || correctAnswer.isBlank()
                ? -1
                : options.indexOf(correctAnswer.trim());
        if (correct < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "correctOptionIndex is out of range.");
        }
        String id = question.id() == null || question.id().isBlank() ? UUID.randomUUID().toString() : question.id();
        return new ChapterQuizPayload.Question(
                id,
                question.question().trim(),
                options,
                correct,
                question.citationParagraphIndex(),
                question.citationSnippet()
        );
    }

    private List<ChapterQuizPayload.Question> parseSuggestedQuestions(String raw, int count, int optionCount)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        JsonNode questions = root.get("questions");
        if (questions == null || !questions.isArray()) {
            throw new IllegalArgumentException("Missing questions array");
        }
        List<ChapterQuizPayload.Question> result = new ArrayList<>();
        for (JsonNode node : questions) {
            ChapterQuizPayload.Question parsed = objectMapper.treeToValue(node, ChapterQuizPayload.Question.class);
            ChapterQuizPayload.Question normalized = normalizeQuestion(parsed);
            if (normalized != null) {
                result.add(normalized);
            }
            if (result.size() >= count) {
                break;
            }
        }
        if (result.size() != count) {
            throw new IllegalArgumentException(
                    "Expected exactly " + count + " questions but received " + result.size());
        }
        return result.stream()
                .map(q -> {
                    List<String> options = q.options();
                    if (options.size() < optionCount) {
                        throw new IllegalArgumentException(
                                "Expected exactly " + optionCount + " options but received " + options.size());
                    }
                    if (options.size() == optionCount) {
                        return q;
                    }
                    int correct = q.correctOptionIndex();
                    List<String> trimmed = new ArrayList<>(options.subList(0, optionCount));
                    if (correct >= optionCount) {
                        trimmed.set(optionCount - 1, options.get(correct));
                        correct = optionCount - 1;
                    }
                    return new ChapterQuizPayload.Question(
                            q.id(), q.question(), trimmed, correct,
                            q.citationParagraphIndex(), q.citationSnippet());
                })
                .toList();
    }

    private List<String> parseDistractors(String raw, String correctAnswer, int count) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        JsonNode node = root.get("distractors");
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("Missing distractors array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            String value = item.asText("").trim();
            if (value.isBlank() || value.equalsIgnoreCase(correctAnswer)) {
                continue;
            }
            result.add(value);
            if (result.size() >= count) {
                break;
            }
        }
        if (result.size() < count) {
            throw new IllegalArgumentException("Expected " + count + " distractors");
        }
        return result;
    }

    private void assertSubmissionComplete(ChapterQuizPayload payload, List<Integer> selectedOptionIndexes) {
        int expected = payload.questions().size();
        if (selectedOptionIndexes == null || selectedOptionIndexes.size() != expected) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Answer every question before submitting (" + expected + " required).");
        }
        for (int i = 0; i < selectedOptionIndexes.size(); i++) {
            Integer selected = selectedOptionIndexes.get(i);
            if (selected == null || selected < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer every question before submitting.");
            }
            var question = payload.questions().get(i);
            int optionCount = question.options() == null ? 0 : question.options().size();
            if (selected >= optionCount) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Answer selection for question " + (i + 1) + " is out of range.");
            }
        }
    }

    private void assertSubmissionMatches(
            ChapterQuizPayload payload, List<String> submittedQuestionIds, String contentVersion) {
        if (contentVersion == null || contentVersion.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "contentVersion is required. Reload the quiz and try again.");
        }
        List<String> currentIds = payload.questions().stream()
                .filter(q -> q != null && q.id() != null && !q.id().isBlank())
                .map(q -> q.id().trim())
                .toList();
        if (submittedQuestionIds != null && !submittedQuestionIds.isEmpty()) {
            List<String> submitted = submittedQuestionIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(String::trim)
                    .toList();
            if (!currentIds.equals(submitted)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This quiz was updated since you opened it. Reload the quiz and try again.");
            }
        }
        String currentVersion = chapterQuizService.contentVersion(payload);
        if (currentVersion == null || !currentVersion.equals(contentVersion.trim())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This quiz was updated since you opened it. Reload the quiz and try again.");
        }
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

    public record AssignmentEffectiveQuizResponse(
            String assignmentId,
            String termId,
            String bookId,
            String quizSource,
            boolean chapterDefaultAvailable,
            List<TeacherQuizAuthoringService.TeacherQuestionView> questions,
            String contentVersion
    ) {
    }

    public record AssignmentQuizViewResponse(
            String assignmentId,
            String bookId,
            String quizSource,
            boolean ready,
            ChapterQuizViewPayload payload
    ) {
    }

    public record SaveAssignmentQuizRequest(List<ChapterQuizPayload.Question> questions) {
    }
}
