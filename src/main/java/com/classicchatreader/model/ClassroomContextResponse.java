package com.classicchatreader.model;

import java.util.List;

public record ClassroomContextResponse(
        boolean enrolled,
        String classId,
        String className,
        String teacherName,
        ClassroomFeatureStates features,
        List<ClassAssignment> assignments,
        String termId,
        String role
) {
    public ClassroomContextResponse(
            boolean enrolled,
            String classId,
            String className,
            String teacherName,
            ClassroomFeatureStates features,
            List<ClassAssignment> assignments) {
        this(enrolled, classId, className, teacherName, features, assignments, null, null);
    }

    public static ClassroomContextResponse notEnrolled() {
        return new ClassroomContextResponse(
                false,
                null,
                null,
                null,
                ClassroomFeatureStates.defaults(),
                List.of(),
                null,
                null
        );
    }

    public record ClassroomFeatureStates(
            boolean quizEnabled,
            boolean recapEnabled,
            boolean ttsEnabled,
            boolean illustrationEnabled,
            boolean characterEnabled,
            boolean chatEnabled,
            boolean speedReadingEnabled,
            boolean readingBuddyEnabled
    ) {
        public static ClassroomFeatureStates defaults() {
            return new ClassroomFeatureStates(true, true, true, true, true, true, true, true);
        }
    }

    public record AssignmentChapterRef(
            String chapterId,
            Integer chapterIndex,
            String chapterTitle
    ) {
    }

    public record ClassAssignment(
            String assignmentId,
            String title,
            String bookId,
            String bookTitle,
            String bookAuthor,
            List<AssignmentChapterRef> chapters,
            String chapterId,
            Integer chapterIndex,
            String chapterTitle,
            String dueAt,
            boolean quizRequired,
            String quizSource,
            QuizRequirementStatus quizStatus,
            boolean characterChatRequired,
            boolean bookAvailable,
            Integer quizPassMinCorrect,
            Integer quizMaxRetries,
            Integer quizAttemptsUsed,
            Integer quizAttemptsAllowed,
            Boolean quizPassed,
            Integer quizBestScorePercent
    ) {
        /** Backward-compatible constructor without character-chat / pass-rule fields. */
        public ClassAssignment(
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
                boolean bookAvailable) {
            this(
                    assignmentId,
                    title,
                    bookId,
                    bookTitle,
                    bookAuthor,
                    chapterId == null ? List.of() : List.of(new AssignmentChapterRef(chapterId, chapterIndex, chapterTitle)),
                    chapterId,
                    chapterIndex,
                    chapterTitle,
                    dueAt,
                    quizRequired,
                    null,
                    quizStatus,
                    false,
                    bookAvailable,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        /** Backward-compatible constructor with character-chat but without pass-rule fields. */
        public ClassAssignment(
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
                boolean characterChatRequired,
                boolean bookAvailable) {
            this(
                    assignmentId,
                    title,
                    bookId,
                    bookTitle,
                    bookAuthor,
                    chapterId == null ? List.of() : List.of(new AssignmentChapterRef(chapterId, chapterIndex, chapterTitle)),
                    chapterId,
                    chapterIndex,
                    chapterTitle,
                    dueAt,
                    quizRequired,
                    null,
                    quizStatus,
                    characterChatRequired,
                    bookAvailable,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    public enum QuizRequirementStatus {
        NOT_REQUIRED,
        PENDING,
        COMPLETE,
        UNKNOWN
    }
}
