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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ObjectMapper objectMapper;

    public ClassroomEffectiveQuizService(
            ClassroomContextService classroomContextService,
            QuizQuestionOverrideRepository overrideRepository,
            ChapterRepository chapterRepository,
            ChapterQuizService chapterQuizService,
            ObjectMapper objectMapper) {
        this.classroomContextService = classroomContextService;
        this.overrideRepository = overrideRepository;
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
