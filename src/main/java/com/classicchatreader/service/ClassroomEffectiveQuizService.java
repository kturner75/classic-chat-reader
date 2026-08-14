package com.classicchatreader.service;

import com.classicchatreader.entity.QuizQuestionOverrideEntity;
import com.classicchatreader.model.ChapterQuizGradeResponse;
import com.classicchatreader.model.ChapterQuizPayload;
import com.classicchatreader.model.ChapterQuizResponse;
import com.classicchatreader.model.ChapterQuizStatusResponse;
import com.classicchatreader.model.ChapterQuizViewPayload;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.QuizQuestionOverrideRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Serves class-scoped effective quizzes to enrolled students when ACTIVE overrides exist.
 */
@Service
public class ClassroomEffectiveQuizService {

    private final ClassroomContextService classroomContextService;
    private final QuizQuestionOverrideRepository overrideRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterQuizService chapterQuizService;
    private final ClassroomQuizPolicyService classroomQuizPolicyService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ClassroomEffectiveQuizService(
            ClassroomContextService classroomContextService,
            QuizQuestionOverrideRepository overrideRepository,
            ChapterRepository chapterRepository,
            ChapterQuizService chapterQuizService,
            ClassroomQuizPolicyService classroomQuizPolicyService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.classroomContextService = classroomContextService;
        this.overrideRepository = overrideRepository;
        this.chapterRepository = chapterRepository;
        this.chapterQuizService = chapterQuizService;
        this.classroomQuizPolicyService = classroomQuizPolicyService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public Optional<ChapterQuizResponse> getChapterQuiz(String chapterId, String userId) {
        Optional<ChapterQuizPayload> effective = resolveEffectivePayload(chapterId, userId);
        Optional<ChapterQuizResponse> base = chapterQuizService.getChapterQuiz(chapterId);
        if (effective.isEmpty()) {
            return base;
        }
        ChapterQuizViewPayload view = chapterQuizService.toStudentView(effective.get());
        if (base.isPresent()) {
            ChapterQuizResponse original = base.get();
            return Optional.of(new ChapterQuizResponse(
                    original.bookId(),
                    original.chapterId(),
                    original.chapterIndex(),
                    original.chapterTitle(),
                    "COMPLETED",
                    true,
                    original.generatedAt(),
                    original.updatedAt(),
                    original.promptVersion(),
                    original.modelName(),
                    original.difficultyLevel(),
                    view
            ));
        }
        return chapterRepository.findByIdWithBook(chapterId).map(chapter -> new ChapterQuizResponse(
                chapter.getBook().getId(),
                chapterId,
                chapter.getChapterIndex(),
                chapter.getTitle(),
                "COMPLETED",
                true,
                null,
                null,
                "teacher-authored",
                null,
                0,
                view
        ));
    }

    @Transactional
    public Optional<ChapterQuizGradeResponse> gradeQuiz(
            String chapterId,
            List<Integer> selectedOptionIndexes,
            String readerId,
            String userId) {
        return gradeQuiz(chapterId, selectedOptionIndexes, null, readerId, userId);
    }

    @Transactional
    public Optional<ChapterQuizGradeResponse> gradeQuiz(
            String chapterId,
            List<Integer> selectedOptionIndexes,
            List<String> questionIds,
            String readerId,
            String userId) {
        return gradeQuiz(chapterId, selectedOptionIndexes, questionIds, null, readerId, userId);
    }

    public Optional<ChapterQuizGradeResponse> gradeQuiz(
            String chapterId,
            List<Integer> selectedOptionIndexes,
            List<String> questionIds,
            String contentVersion,
            String readerId,
            String userId) {
        // Hold the lock around the full transaction so concurrent last-attempt
        // submissions cannot both observe the pre-commit attempt count.
        // Prefer account userId; fall back to readerId so anonymous graders do not
        // all serialize on the empty-user key.
        String identityKey = (userId != null && !userId.isBlank())
                ? userId
                : (readerId == null ? "" : readerId);
        String lockKey = (identityKey + "\u0000" + (chapterId == null ? "" : chapterId)).intern();
        synchronized (lockKey) {
            return transactionTemplate.execute(status -> {
                // Shared content lock with teacher republish (chapter + quiz rows).
                // PESSIMISTIC_READ so classmates grade concurrently; publishers still exclusive.
                chapterQuizService.lockQuizContentShared(chapterId);
                // Per-user attempt budget reservation (not shared assignment locks).
                classroomQuizPolicyService.assertCanAttempt(chapterId, userId);
                Optional<ChapterQuizPayload> effective = resolveEffectivePayload(chapterId, userId);
                ChapterQuizPayload versioned = effective.orElseGet(
                        () -> chapterQuizService.loadCompletedPayloadWithIdBackfill(chapterId));
                assertSubmissionMatchesDisplayedQuiz(versioned, questionIds, contentVersion);
                assertSubmissionComplete(versioned, selectedOptionIndexes);

                if (effective.isEmpty()) {
                    return chapterQuizService.gradeQuiz(chapterId, selectedOptionIndexes, readerId, userId);
                }
                return chapterQuizService.gradeQuizWithPayload(
                        chapterId, selectedOptionIndexes, readerId, userId, effective.get());
            });
        }
    }

    private void assertSubmissionComplete(ChapterQuizPayload payload, List<Integer> selectedOptionIndexes) {
        int expected = payload == null || payload.questions() == null ? 0 : payload.questions().size();
        if (expected <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiz has no questions to grade.");
        }
        if (selectedOptionIndexes == null || selectedOptionIndexes.size() != expected) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Answer every question before submitting (" + expected + " required).");
        }
        for (int i = 0; i < selectedOptionIndexes.size(); i++) {
            Integer selected = selectedOptionIndexes.get(i);
            if (selected == null || selected < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Answer every question before submitting.");
            }
            var question = payload.questions().get(i);
            int optionCount = question == null || question.options() == null ? 0 : question.options().size();
            if (selected >= optionCount) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Answer selection for question " + (i + 1) + " is out of range.");
            }
        }
    }

    private void assertSubmissionMatchesDisplayedQuiz(
            ChapterQuizPayload payload, List<String> submittedQuestionIds, String contentVersion) {
        if (contentVersion == null || contentVersion.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "contentVersion is required. Reload the quiz and try again.");
        }
        List<String> currentIds = payload == null || payload.questions() == null
                ? List.of()
                : payload.questions().stream()
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

    @Transactional
    public Optional<ChapterQuizStatusResponse> getChapterQuizStatus(String chapterId, String userId) {
        Optional<ChapterQuizPayload> effective = resolveEffectivePayload(chapterId, userId);
        if (effective.isPresent()) {
            return chapterRepository.findByIdWithBook(chapterId).map(chapter -> new ChapterQuizStatusResponse(
                    chapter.getBook().getId(),
                    chapterId,
                    "COMPLETED",
                    true,
                    null,
                    null
            ));
        }
        return chapterQuizService.getChapterQuizStatus(chapterId);
    }

    /**
     * Effective question count for a term/chapter after overlays. Empty when no completed base
     * and no usable active overrides (unknown size).
     */
    @Transactional
    public Optional<Integer> resolveEffectiveQuestionCount(String termId, String chapterId) {
        if (termId == null || termId.isBlank() || chapterId == null || chapterId.isBlank()) {
            return Optional.empty();
        }
        ChapterQuizPayload generated = chapterQuizService.loadCompletedPayloadWithIdBackfill(chapterId);
        List<QuizQuestionOverrideEntity> active = overrideRepository
                .findByTermIdAndChapterIdAndStatusAndDeletedAtIsNullOrderBySortOrderAsc(
                        termId, chapterId, QuizQuestionOverrideEntity.STATUS_ACTIVE);
        if ((generated.questions() == null || generated.questions().isEmpty()) && active.isEmpty()) {
            return Optional.empty();
        }
        EffectiveQuizAssembler.MergeResult merged =
                EffectiveQuizAssembler.merge(generated, active, objectMapper);
        if (merged.effective().questions() == null || merged.effective().questions().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merged.effective().questions().size());
    }

    @Transactional(readOnly = true)
    public Optional<ChapterQuizPayload> loadEffectivePayloadForTerm(String termId, String chapterId) {
        if (termId == null || termId.isBlank() || chapterId == null || chapterId.isBlank()) {
            return Optional.empty();
        }
        ChapterQuizPayload generated = chapterQuizService.loadCompletedPayloadWithIdBackfill(chapterId);
        List<QuizQuestionOverrideEntity> active = overrideRepository
                .findByTermIdAndChapterIdAndStatusAndDeletedAtIsNullOrderBySortOrderAsc(
                        termId, chapterId, QuizQuestionOverrideEntity.STATUS_ACTIVE);
        if ((generated.questions() == null || generated.questions().isEmpty()) && active.isEmpty()) {
            return Optional.empty();
        }
        EffectiveQuizAssembler.MergeResult merged =
                EffectiveQuizAssembler.merge(generated, active, objectMapper);
        if (merged.effective().questions() == null || merged.effective().questions().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merged.effective());
    }

    public int countQuestions(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return 0;
        }
        try {
            ChapterQuizPayload payload = objectMapper.readValue(payloadJson, ChapterQuizPayload.class);
            return payload.questions() == null ? 0 : payload.questions().size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Optional<ChapterQuizPayload> resolveEffectivePayload(String chapterId, String userId) {
        if (userId == null || userId.isBlank() || chapterId == null || chapterId.isBlank()) {
            return Optional.empty();
        }
        // Match the same active/preferred term ClassroomContextService exposes to the reader.
        ClassroomContextResponse context = classroomContextService.getContext(userId);
        if (!context.enrolled() || context.termId() == null || context.termId().isBlank()) {
            return Optional.empty();
        }
        String termId = context.termId();
        boolean hasOverrides = overrideRepository
                .existsByTermIdAndChapterIdAndStatusAndDeletedAtIsNull(
                        termId, chapterId, QuizQuestionOverrideEntity.STATUS_ACTIVE);
        if (!hasOverrides) {
            return Optional.empty();
        }
        ChapterQuizPayload generated = chapterQuizService.loadCompletedPayloadWithIdBackfill(chapterId);
        List<QuizQuestionOverrideEntity> active = overrideRepository
                .findByTermIdAndChapterIdAndStatusAndDeletedAtIsNullOrderBySortOrderAsc(
                        termId, chapterId, QuizQuestionOverrideEntity.STATUS_ACTIVE);
        EffectiveQuizAssembler.MergeResult merged =
                EffectiveQuizAssembler.merge(generated, active, objectMapper);
        if (merged.effective().questions() == null || merged.effective().questions().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(merged.effective());
    }
}
