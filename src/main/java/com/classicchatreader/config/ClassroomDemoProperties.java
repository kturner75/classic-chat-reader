package com.classicchatreader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "classroom.demo")
public class ClassroomDemoProperties {

    private boolean enabled = false;
    private String classId;
    private String className;
    private String teacherName;
    private Features features = new Features();
    private List<Assignment> assignments = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getTeacherName() {
        return teacherName;
    }

    public void setTeacherName(String teacherName) {
        this.teacherName = teacherName;
    }

    public Features getFeatures() {
        return features;
    }

    public void setFeatures(Features features) {
        this.features = features == null ? new Features() : features;
    }

    public List<Assignment> getAssignments() {
        return assignments;
    }

    public void setAssignments(List<Assignment> assignments) {
        this.assignments = assignments == null ? new ArrayList<>() : assignments;
    }

    public static class Features {
        private boolean quizEnabled = true;
        private boolean recapEnabled = true;
        private boolean ttsEnabled = true;
        private boolean illustrationEnabled = true;
        private boolean characterEnabled = true;
        private boolean chatEnabled = true;
        private boolean speedReadingEnabled = true;

        public boolean isQuizEnabled() {
            return quizEnabled;
        }

        public void setQuizEnabled(boolean quizEnabled) {
            this.quizEnabled = quizEnabled;
        }

        public boolean isRecapEnabled() {
            return recapEnabled;
        }

        public void setRecapEnabled(boolean recapEnabled) {
            this.recapEnabled = recapEnabled;
        }

        public boolean isTtsEnabled() {
            return ttsEnabled;
        }

        public void setTtsEnabled(boolean ttsEnabled) {
            this.ttsEnabled = ttsEnabled;
        }

        public boolean isIllustrationEnabled() {
            return illustrationEnabled;
        }

        public void setIllustrationEnabled(boolean illustrationEnabled) {
            this.illustrationEnabled = illustrationEnabled;
        }

        public boolean isCharacterEnabled() {
            return characterEnabled;
        }

        public void setCharacterEnabled(boolean characterEnabled) {
            this.characterEnabled = characterEnabled;
        }

        public boolean isChatEnabled() {
            return chatEnabled;
        }

        public void setChatEnabled(boolean chatEnabled) {
            this.chatEnabled = chatEnabled;
        }

        public boolean isSpeedReadingEnabled() {
            return speedReadingEnabled;
        }

        public void setSpeedReadingEnabled(boolean speedReadingEnabled) {
            this.speedReadingEnabled = speedReadingEnabled;
        }
    }

    public static class Assignment {
        private String assignmentId;
        private String title;
        private String bookId;
        private String chapterId;
        private Integer chapterIndex;
        private String dueAt;
        private boolean quizRequired = false;

        public String getAssignmentId() {
            return assignmentId;
        }

        public void setAssignmentId(String assignmentId) {
            this.assignmentId = assignmentId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getBookId() {
            return bookId;
        }

        public void setBookId(String bookId) {
            this.bookId = bookId;
        }

        public String getChapterId() {
            return chapterId;
        }

        public void setChapterId(String chapterId) {
            this.chapterId = chapterId;
        }

        public Integer getChapterIndex() {
            return chapterIndex;
        }

        public void setChapterIndex(Integer chapterIndex) {
            this.chapterIndex = chapterIndex;
        }

        public String getDueAt() {
            return dueAt;
        }

        public void setDueAt(String dueAt) {
            this.dueAt = dueAt;
        }

        public boolean isQuizRequired() {
            return quizRequired;
        }

        public void setQuizRequired(boolean quizRequired) {
            this.quizRequired = quizRequired;
        }
    }
}
