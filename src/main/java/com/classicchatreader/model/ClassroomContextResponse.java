package com.classicchatreader.model;

import java.util.List;

public record ClassroomContextResponse(
        boolean enrolled,
        String classId,
        String className,
        String teacherName,
        ClassroomFeatureStates features,
        List<ClassAssignment> assignments
) {
    public static ClassroomContextResponse notEnrolled() {
        return new ClassroomContextResponse(
                false,
                null,
                null,
                null,
                ClassroomFeatureStates.defaults(),
                List.of()
        );
    }

    public record ClassroomFeatureStates(
            boolean quizEnabled,
            boolean recapEnabled,
            boolean ttsEnabled,
            boolean illustrationEnabled,
            boolean characterEnabled,
            boolean chatEnabled,
            boolean speedReadingEnabled
    ) {
        public static ClassroomFeatureStates defaults() {
            return new ClassroomFeatureStates(true, true, true, true, true, true, true);
        }
    }

    public record ClassAssignment(
            String assignmentId,
            String title,
            String bookId,
            String bookTitle,
            String bookAuthor,
            String chapterId,
            Integer chapterIndex,
            String chapterTitle,
            String dueAt,
            boolean quizRequired,
            QuizRequirementStatus quizStatus,
            boolean bookAvailable
    ) {
    }

    public enum QuizRequirementStatus {
        NOT_REQUIRED,
        PENDING,
        COMPLETE,
        UNKNOWN
    }
}
