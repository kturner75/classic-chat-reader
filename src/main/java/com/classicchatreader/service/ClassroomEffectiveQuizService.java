package com.classicchatreader.service;

import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ChapterQuizEntity;
import com.classicchatreader.entity.ChapterQuizStatus;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.entity.QuizQuestionOverrideEntity;
import com.classicchatreader.model.ChapterQuizGradeResponse;
import com.classicchatreader.model.ChapterQuizPayload;
import com.classicchatreader.model.ChapterQuizResponse;
import com.classicchatreader.model.ChapterQuizStatusResponse;
import com.classicchatreader.model.ChapterQuizViewPayload;
import com.classicchatreader.repository.ChapterQuizRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizQuestionOverrideRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Serves class-scoped effective quizzes to enrolled students when ACTIVE overrides exist.
 */
@Service
public class ClassroomEffectiveQuizService {

    private final EnrollmentRepository enrollmentRepository;
    private final QuizQuestionOverrideRepository overrideRepository;
    private final ChapterQuizRepository chapterQuizRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterQuizService chapterQuizService;
    private final ObjectMapper objectMapper;

    public ClassroomEffectiveQuizService(
            EnrollmentRepository enrollmentRepository,
            QuizQuestionOverrideRepository overrideRepository,
            ChapterQuizRepository chapterQuizRepository,
            ChapterRepository chapterRepository,
            ChapterQuizService chapterQuizService,
            ObjectMapper objectMapper) {
        this.enrollmentRepository = enrollmentRepository;
        this.overrideRepository = overrideRepository;
        this.chapterQuizRepository = chapterQuizRepository;
        this.chapterRepository = chapterRepository;
        this.chapterQuizService = chapterQuizService;
        this.objectMapper = objectMapper;
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
                    original.status(),
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
        Optional<ChapterQuizPayload> effective = resolveEffectivePayload(chapterId, userId);
        if (effective.isEmpty()) {
            return chapterQuizService.gradeQuiz(chapterId, selectedOptionIndexes, readerId, userId);
        }
        return chapterQuizService.gradeQuizWithPayload(
                chapterId, selectedOptionIndexes, readerId, userId, effective.get());
    }

    @Transactional(readOnly = true)
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

    private Optional<ChapterQuizPayload> resolveEffectivePayload(String chapterId, String userId) {
        if (userId == null || userId.isBlank() || chapterId == null || chapterId.isBlank()) {
            return Optional.empty();
        }
        List<EnrollmentEntity> enrollments = enrollmentRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, "ACTIVE");
        for (EnrollmentEntity enrollment : enrollments) {
            String termId = enrollment.getTermId();
            if (termId == null) {
                continue;
            }
            boolean hasOverrides = overrideRepository
                    .existsByTermIdAndChapterIdAndStatusAndDeletedAtIsNull(
                            termId, chapterId, QuizQuestionOverrideEntity.STATUS_ACTIVE);
            if (!hasOverrides) {
                continue;
            }
            Optional<ChapterQuizEntity> quizOpt = chapterQuizRepository.findByChapterId(chapterId);
            ChapterQuizPayload generated = quizOpt
                    .filter(q -> q.getStatus() == ChapterQuizStatus.COMPLETED)
                    .map(q -> chapterQuizService.parsePayloadJson(q.getPayloadJson()))
                    .orElseGet(() -> new ChapterQuizPayload(List.of()));
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
        return Optional.empty();
    }
}
