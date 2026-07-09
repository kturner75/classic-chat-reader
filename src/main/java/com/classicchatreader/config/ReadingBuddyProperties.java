package com.classicchatreader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Configuration for Reading Buddy Mode ({@code reading-buddy.*}).
 * Off by default; later PRs use these knobs for trigger policy, chat, and memory.
 */
@Component
@ConfigurationProperties(prefix = "reading-buddy")
public class ReadingBuddyProperties {

    private boolean enabled = false;
    private MinParagraphGap minParagraphGap = new MinParagraphGap();
    private MinCooldownMs minCooldownMs = new MinCooldownMs();
    private int maxCommentsPerChapter = 6;
    private int maxCommentsPerHour = 12;
    private Proactive proactive = new Proactive();
    private Chat chat = new Chat();
    private Memory memory = new Memory();
    private StoryContext storyContext = new StoryContext();
    private int quietDefaultMinutes = 45;
    private int userMessageMaxChars = 2000;
    private int postChatParagraphGap = 4;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public MinParagraphGap getMinParagraphGap() {
        return minParagraphGap;
    }

    public void setMinParagraphGap(MinParagraphGap minParagraphGap) {
        this.minParagraphGap = minParagraphGap == null ? new MinParagraphGap() : minParagraphGap;
    }

    public MinCooldownMs getMinCooldownMs() {
        return minCooldownMs;
    }

    public void setMinCooldownMs(MinCooldownMs minCooldownMs) {
        this.minCooldownMs = minCooldownMs == null ? new MinCooldownMs() : minCooldownMs;
    }

    public int getMaxCommentsPerChapter() {
        return maxCommentsPerChapter;
    }

    public void setMaxCommentsPerChapter(int maxCommentsPerChapter) {
        this.maxCommentsPerChapter = maxCommentsPerChapter;
    }

    public int getMaxCommentsPerHour() {
        return maxCommentsPerHour;
    }

    public void setMaxCommentsPerHour(int maxCommentsPerHour) {
        this.maxCommentsPerHour = maxCommentsPerHour;
    }

    public Proactive getProactive() {
        return proactive;
    }

    public void setProactive(Proactive proactive) {
        this.proactive = proactive == null ? new Proactive() : proactive;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat == null ? new Chat() : chat;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory == null ? new Memory() : memory;
    }

    public StoryContext getStoryContext() {
        return storyContext;
    }

    public void setStoryContext(StoryContext storyContext) {
        this.storyContext = storyContext == null ? new StoryContext() : storyContext;
    }

    public int getQuietDefaultMinutes() {
        return quietDefaultMinutes;
    }

    public void setQuietDefaultMinutes(int quietDefaultMinutes) {
        this.quietDefaultMinutes = quietDefaultMinutes;
    }

    public int getUserMessageMaxChars() {
        return userMessageMaxChars;
    }

    public void setUserMessageMaxChars(int userMessageMaxChars) {
        this.userMessageMaxChars = userMessageMaxChars;
    }

    public int getPostChatParagraphGap() {
        return postChatParagraphGap;
    }

    public void setPostChatParagraphGap(int postChatParagraphGap) {
        this.postChatParagraphGap = postChatParagraphGap;
    }

    /**
     * Returns the min paragraph gap for a frequency preference ({@code rare}, {@code occasional}, {@code chatty}).
     * Unknown values fall back to rare.
     */
    public int minParagraphGapFor(String frequency) {
        if (frequency == null) {
            return minParagraphGap.getRare();
        }
        return switch (frequency.toLowerCase(Locale.ROOT)) {
            case "occasional" -> minParagraphGap.getOccasional();
            case "chatty" -> minParagraphGap.getChatty();
            default -> minParagraphGap.getRare();
        };
    }

    /**
     * Returns the min wall-clock cooldown (ms) for a frequency preference.
     * Unknown values fall back to rare.
     */
    public long minCooldownMsFor(String frequency) {
        if (frequency == null) {
            return minCooldownMs.getRare();
        }
        return switch (frequency.toLowerCase(Locale.ROOT)) {
            case "occasional" -> minCooldownMs.getOccasional();
            case "chatty" -> minCooldownMs.getChatty();
            default -> minCooldownMs.getRare();
        };
    }

    public static class MinParagraphGap {
        private int rare = 8;
        private int occasional = 4;
        private int chatty = 2;

        public int getRare() {
            return rare;
        }

        public void setRare(int rare) {
            this.rare = rare;
        }

        public int getOccasional() {
            return occasional;
        }

        public void setOccasional(int occasional) {
            this.occasional = occasional;
        }

        public int getChatty() {
            return chatty;
        }

        public void setChatty(int chatty) {
            this.chatty = chatty;
        }
    }

    public static class MinCooldownMs {
        private long rare = 180_000L;
        private long occasional = 90_000L;
        private long chatty = 45_000L;

        public long getRare() {
            return rare;
        }

        public void setRare(long rare) {
            this.rare = rare;
        }

        public long getOccasional() {
            return occasional;
        }

        public void setOccasional(long occasional) {
            this.occasional = occasional;
        }

        public long getChatty() {
            return chatty;
        }

        public void setChatty(long chatty) {
            this.chatty = chatty;
        }
    }

    public static class Proactive {
        private int maxWords = 60;

        public int getMaxWords() {
            return maxWords;
        }

        public void setMaxWords(int maxWords) {
            this.maxWords = maxWords;
        }
    }

    public static class Chat {
        private int maxWords = 150;
        private int maxContextMessages = 12;

        public int getMaxWords() {
            return maxWords;
        }

        public void setMaxWords(int maxWords) {
            this.maxWords = maxWords;
        }

        public int getMaxContextMessages() {
            return maxContextMessages;
        }

        public void setMaxContextMessages(int maxContextMessages) {
            this.maxContextMessages = maxContextMessages;
        }
    }

    public static class Memory {
        private int summaryMaxChars = 1500;
        private int recentMessages = 20;
        private int summaryEveryMessages = 8;

        public int getSummaryMaxChars() {
            return summaryMaxChars;
        }

        public void setSummaryMaxChars(int summaryMaxChars) {
            this.summaryMaxChars = summaryMaxChars;
        }

        public int getRecentMessages() {
            return recentMessages;
        }

        public void setRecentMessages(int recentMessages) {
            this.recentMessages = recentMessages;
        }

        public int getSummaryEveryMessages() {
            return summaryEveryMessages;
        }

        public void setSummaryEveryMessages(int summaryEveryMessages) {
            this.summaryEveryMessages = summaryEveryMessages;
        }
    }

    /**
     * Position-bounded paragraph window injected as STORY CONTEXT (spoiler-safe source text).
     */
    public static class StoryContext {
        /** Max characters of paragraph text in STORY CONTEXT (similar to recap.chat.max-source-chars). */
        private int maxSourceChars = 4000;
        /** Number of prior paragraphs in the same chapter to include (in addition to current). */
        private int priorParagraphs = 2;
        /** When true, also include the first paragraph of the chapter if not already in the window. */
        private boolean includeChapterFirstParagraph = true;

        public int getMaxSourceChars() {
            return maxSourceChars;
        }

        public void setMaxSourceChars(int maxSourceChars) {
            this.maxSourceChars = maxSourceChars;
        }

        public int getPriorParagraphs() {
            return priorParagraphs;
        }

        public void setPriorParagraphs(int priorParagraphs) {
            this.priorParagraphs = priorParagraphs;
        }

        public boolean isIncludeChapterFirstParagraph() {
            return includeChapterFirstParagraph;
        }

        public void setIncludeChapterFirstParagraph(boolean includeChapterFirstParagraph) {
            this.includeChapterFirstParagraph = includeChapterFirstParagraph;
        }
    }
}
