package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomDemoProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ClassroomContextResponse.ClassAssignment;
import com.classicchatreader.model.ClassroomContextResponse.ClassroomFeatureStates;
import com.classicchatreader.model.ClassroomContextResponse.QuizRequirementStatus;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ClassroomContextService {

    private static final String DEFAULT_CLASS_ID = "demo-class";
    private static final String DEFAULT_CLASS_NAME = "Assigned Reading";

    private final ClassroomDemoProperties classroomDemoProperties;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final QuizAttemptRepository quizAttemptRepository;

    public ClassroomContextService(
            ClassroomDemoProperties classroomDemoProperties,
            BookRepository bookRepository,
            ChapterRepository chapterRepository,
            QuizAttemptRepository quizAttemptRepository) {
        this.classroomDemoProperties = classroomDemoProperties;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
        this.quizAttemptRepository = quizAttemptRepository;
    }

    public ClassroomContextResponse getContext() {
        if (!classroomDemoProperties.isEnabled()) {
            return ClassroomContextResponse.notEnrolled();
        }

        List<ClassAssignment> assignments = buildAssignments();
        ClassroomFeatureStates features = resolveFeatureStates();

        return new ClassroomContextResponse(
                true,
                normalizeOrDefault(classroomDemoProperties.getClassId(), DEFAULT_CLASS_ID),
                normalizeOrDefault(classroomDemoProperties.getClassName(), DEFAULT_CLASS_NAME),
                normalizeOrNull(classroomDemoProperties.getTeacherName()),
                features,
                assignments
        );
    }

    private List<ClassAssignment> buildAssignments() {
        List<ClassroomDemoProperties.Assignment> configured = classroomDemoProperties.getAssignments();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }

        List<ClassAssignment> resolved = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            ClassAssignment assignment = resolveAssignment(configured.get(i), i);
            if (assignment != null) {
                resolved.add(assignment);
            }
        }
        return resolved;
    }

    private ClassAssignment resolveAssignment(ClassroomDemoProperties.Assignment configured, int index) {
        if (configured == null) {
            return null;
        }

        String bookId = normalizeOrNull(configured.getBookId());
        if (bookId == null) {
            return null;
        }

        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        Optional<ChapterEntity> chapterOpt = resolveChapter(configured, bookId);

        String assignmentId = normalizeOrDefault(
                configured.getAssignmentId(),
                "assignment-" + (index + 1)
        );
        String title = normalizeOrDefault(configured.getTitle(), "Assigned Reading");
        String bookTitle = bookOpt.map(BookEntity::getTitle).orElse("Book unavailable");
        String bookAuthor = bookOpt.map(BookEntity::getAuthor).orElse("");
        String chapterId = chapterOpt.map(ChapterEntity::getId).orElse(null);
        Integer chapterIndex = chapterOpt.map(ChapterEntity::getChapterIndex).orElse(configured.getChapterIndex());
        String chapterTitle = chapterOpt.map(ChapterEntity::getTitle).orElseGet(() -> {
            Integer configuredIndex = configured.getChapterIndex();
            if (configuredIndex == null) {
                return null;
            }
            return "Chapter " + Math.max(1, configuredIndex + 1);
        });
        String dueAt = normalizeOrNull(configured.getDueAt());
        boolean quizRequired = configured.isQuizRequired();
        QuizRequirementStatus quizStatus = resolveQuizStatus(quizRequired, chapterId);

        return new ClassAssignment(
                assignmentId,
                title,
                bookId,
                bookTitle,
                bookAuthor,
                chapterId,
                chapterIndex,
                chapterTitle,
                dueAt,
                quizRequired,
                quizStatus,
                bookOpt.isPresent()
        );
    }

    private Optional<ChapterEntity> resolveChapter(ClassroomDemoProperties.Assignment configured, String bookId) {
        String chapterId = normalizeOrNull(configured.getChapterId());
        if (chapterId != null) {
            return chapterRepository.findByIdWithBook(chapterId)
                    .filter(chapter -> Objects.equals(chapter.getBook().getId(), bookId));
        }

        Integer chapterIndex = configured.getChapterIndex();
        if (chapterIndex == null) {
            return Optional.empty();
        }

        return chapterRepository.findByBookIdAndChapterIndex(bookId, Math.max(0, chapterIndex));
    }

    private QuizRequirementStatus resolveQuizStatus(boolean quizRequired, String chapterId) {
        if (!quizRequired) {
            return QuizRequirementStatus.NOT_REQUIRED;
        }
        if (chapterId == null || chapterId.isBlank()) {
            return QuizRequirementStatus.UNKNOWN;
        }
        return quizAttemptRepository.existsByChapterId(chapterId)
                ? QuizRequirementStatus.COMPLETE
                : QuizRequirementStatus.PENDING;
    }

    private ClassroomFeatureStates resolveFeatureStates() {
        ClassroomDemoProperties.Features configured = classroomDemoProperties.getFeatures();
        if (configured == null) {
            return ClassroomFeatureStates.defaults();
        }

        return new ClassroomFeatureStates(
                configured.isQuizEnabled(),
                configured.isRecapEnabled(),
                configured.isTtsEnabled(),
                configured.isIllustrationEnabled(),
                configured.isCharacterEnabled(),
                configured.isChatEnabled(),
                configured.isSpeedReadingEnabled()
        );
    }

    private String normalizeOrNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalizeOrNull(value);
        return normalized == null ? fallback : normalized;
    }
}
