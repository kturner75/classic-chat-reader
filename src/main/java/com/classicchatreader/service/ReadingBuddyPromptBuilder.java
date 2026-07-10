package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.model.ReadingBuddyPersona;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Assembles spoiler-safe reading-buddy prompts: persona structure (character-chat style)
 * plus position-bounded STORY CONTEXT (recap-chat style paragraph window).
 * <p>
 * No HTTP/LLM calls here — pure prompt assembly for chat and proactive paths.
 * High-level chat/proactive builders always apply summary watermark omit via
 * {@link #resolveMemorySummaryForPosition} so callers cannot skip the spoiler gate.
 */
@Component
public class ReadingBuddyPromptBuilder {

    private final ReadingBuddyStoryContextLoader storyContextLoader;
    private final ReadingBuddyProperties properties;

    public ReadingBuddyPromptBuilder(
            ReadingBuddyStoryContextLoader storyContextLoader,
            ReadingBuddyProperties properties) {
        this.storyContextLoader = storyContextLoader;
        this.properties = properties;
    }

    /**
     * System / persona block with position binding, story-boundary, commentary style,
     * and (for historian) non-plot carve-out reinforcement.
     * <p>
     * STORY BOUNDARY / COMMENTARY STYLE live here (authoritative, position-bound).
     * Catalog {@link ReadingBuddyPersona#systemPrompt()} supplies persona voice only.
     */
    public String buildSystemPrompt(
            ReadingBuddyPersona persona,
            String bookTitle,
            String author,
            int chapterIndex,
            String chapterTitle,
            int paragraphIndex) {
        Objects.requireNonNull(persona, "persona");
        String title = blankTo(bookTitle, "this book");
        String auth = blankTo(author, "unknown author");
        String chTitle = blankTo(chapterTitle, "Chapter " + (chapterIndex + 1));

        int maxProactive = persona.maxProactiveWords() > 0
                ? persona.maxProactiveWords()
                : properties.getProactive().getMaxWords();
        int maxChat = persona.maxChatWords() > 0
                ? persona.maxChatWords()
                : properties.getChat().getMaxWords();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, """
                You are %s, a reading buddy for "%s" by %s.
                You are NOT a character in the book. Address the reader as a modern-day reader.

                STORY BOUNDARY (CRITICAL):
                - Reader is at chapter index %d ("%s"), paragraph index %d.
                - For ANY plot, character fate, relationship outcome, twist, death, marriage, or "what happens next":
                  you may ONLY use STORY CONTEXT and MEMORY below. Treat outside model knowledge of this book as unknown.
                - Never hint at upcoming events or endings.
                - If asked about the future or unrevealed plot, deflect: you only know what they've read so far.
                """,
                persona.displayName(),
                title,
                auth,
                chapterIndex,
                chTitle,
                paragraphIndex));

        sb.append("""

                NON-PLOT CONTEXT (historian / period color only):
                - You may share general period customs, language notes, or widely known author biography that does NOT
                  reveal or imply this book's plot outcomes.
                - If unsure whether a fact is plot-adjacent, omit it and stay with the passage.
                """);

        if (ReadingBuddyPersonaCatalog.HISTORIAN.equals(persona.id())) {
            sb.append("""
                    - Historian emphasis: prefer NON-PLOT period hooks only; never use outside knowledge for plot.
                    """);
        }

        sb.append(String.format(Locale.ROOT, """

                COMMENTARY STYLE:
                - Proactive comments ≤ %d words; chat replies ≤ %d words.
                - Be relevant to the CURRENT PARAGRAPH; do not rehash the whole chapter.
                - Never moralize aggressively; match a calm reading tone.

                PERSONA INSTRUCTIONS:
                %s
                """,
                maxProactive,
                maxChat,
                blankTo(persona.systemPrompt(), "").trim()));

        return sb.toString().stripTrailing();
    }

    /**
     * Loads and formats the position-bounded STORY CONTEXT section body (no header).
     */
    public String loadStoryContext(String bookId, int chapterIndex, int paragraphIndex) {
        return storyContextLoader.loadStoryContext(bookId, chapterIndex, paragraphIndex);
    }

    /**
     * STORY CONTEXT section including header. Empty body becomes a safe placeholder.
     */
    public String buildStoryContextSection(String storyContextBody) {
        String body = storyContextBody == null ? "" : storyContextBody.trim();
        if (body.isEmpty()) {
            body = "(No passage text available at this position.)";
        }
        return "STORY CONTEXT (only text the reader has reached; never future paragraphs):\n" + body;
    }

    /**
     * MEMORY section. Empty summary yields an explicit empty marker (allowed in 3a).
     */
    public String buildMemorySection(String memorySummary) {
        String body = memorySummary == null ? "" : memorySummary.trim();
        if (body.isEmpty()) {
            body = "(No memory yet.)";
        }
        return "MEMORY:\n" + body;
    }

    /**
     * Inject memory summary only when usable at the current position (watermark rule).
     * If summary watermarks are ahead of the reader (rewind), omit summary text.
     * Partial watermarks fail closed (omit).
     */
    public String resolveMemorySummaryForPosition(
            String memorySummary,
            Integer summaryMaxChapterIndex,
            Integer summaryMaxParagraphIndex,
            int readerChapterIndex,
            int readerParagraphIndex) {
        if (memorySummary == null || memorySummary.isBlank()) {
            return "";
        }
        if (!shouldIncludeSummary(
                summaryMaxChapterIndex,
                summaryMaxParagraphIndex,
                readerChapterIndex,
                readerParagraphIndex)) {
            return "";
        }
        int maxChars = Math.max(0, properties.getMemory().getSummaryMaxChars());
        String trimmed = memorySummary.trim();
        if (maxChars > 0 && trimmed.length() > maxChars) {
            return trimmed.substring(0, maxChars).trim();
        }
        return trimmed;
    }

    /**
     * Proactive decide-or-comment task block (includes SPARSITY + historian prefer-NONE bias).
     */
    public String buildProactiveTaskPrompt() {
        return buildProactiveTaskPrompt(null);
    }

    public String buildProactiveTaskPrompt(ReadingBuddyPersona persona) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                SPARSITY (proactive only):
                - If the passage is transitional/mundane, respond with NONE: <reason>
                - Only comment when you have a clear, relevant aside for the CURRENT PARAGRAPH.
                - Respond with exactly one line in one of these forms:
                  COMMENT: <text>
                  NONE: <short reason>
                """);
        if (persona != null && ReadingBuddyPersonaCatalog.HISTORIAN.equals(persona.id())) {
            sb.append("""
                    - Historian bias: prefer NONE unless a clear non-plot period hook exists in the passage.
                    - Do not invent plot color; lower creativity for proactive comments.
                    """);
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Full proactive prompt: system + story context + memory + sparsity task.
     * <p>
     * Always resolves memory via watermark omit — pass stored summary + watermarks from memory row.
     * Do not pre-inject raw summary text; this method is the spoiler gate for MEMORY.
     */
    public String buildProactivePrompt(
            ReadingBuddyPersona persona,
            String bookTitle,
            String author,
            int chapterIndex,
            String chapterTitle,
            int paragraphIndex,
            String storyContextBody,
            String memorySummary,
            Integer summaryMaxChapterIndex,
            Integer summaryMaxParagraphIndex) {
        String resolvedMemory = resolveMemorySummaryForPosition(
                memorySummary,
                summaryMaxChapterIndex,
                summaryMaxParagraphIndex,
                chapterIndex,
                paragraphIndex);
        return joinSections(
                buildSystemPrompt(persona, bookTitle, author, chapterIndex, chapterTitle, paragraphIndex),
                buildStoryContextSection(storyContextBody),
                buildMemorySection(resolvedMemory),
                buildProactiveTaskPrompt(persona));
    }

    /**
     * Convenience when there is no stored summary yet (empty MEMORY).
     */
    public String buildProactivePrompt(
            ReadingBuddyPersona persona,
            String bookTitle,
            String author,
            int chapterIndex,
            String chapterTitle,
            int paragraphIndex,
            String storyContextBody) {
        return buildProactivePrompt(
                persona, bookTitle, author, chapterIndex, chapterTitle, paragraphIndex,
                storyContextBody, "", null, null);
    }

    /**
     * Full proactive prompt with story context loaded from repositories.
     * Watermark fields are required so MEMORY omit cannot be skipped by callers.
     */
    public String buildProactivePromptForPosition(
            ReadingBuddyPersona persona,
            String bookId,
            String bookTitle,
            String author,
            int chapterIndex,
            String chapterTitle,
            int paragraphIndex,
            String memorySummary,
            Integer summaryMaxChapterIndex,
            Integer summaryMaxParagraphIndex) {
        String story = loadStoryContext(bookId, chapterIndex, paragraphIndex);
        return buildProactivePrompt(
                persona,
                bookTitle,
                author,
                chapterIndex,
                chapterTitle,
                paragraphIndex,
                story,
                memorySummary,
                summaryMaxChapterIndex,
                summaryMaxParagraphIndex);
    }

    /**
     * Conversation block from position-filtered messages (already filtered or raw + filter here).
     */
    public String buildConversationSection(List<ReadingBuddyPositionedMessage> messages, int maxMessages) {
        List<ReadingBuddyPositionedMessage> safe = messages == null ? List.of() : messages;
        if (maxMessages > 0 && safe.size() > maxMessages) {
            safe = safe.subList(safe.size() - maxMessages, safe.size());
        }
        if (safe.isEmpty()) {
            return "CONVERSATION:\n(No prior messages.)";
        }
        StringBuilder sb = new StringBuilder("CONVERSATION:\n");
        for (ReadingBuddyPositionedMessage msg : safe) {
            if (msg == null || msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            String roleLabel = conversationRoleLabel(msg.role());
            sb.append(roleLabel).append(": ").append(msg.content().trim()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Full interactive chat prompt pieces assembled.
     * <p>
     * Always resolves memory via watermark omit — pass stored summary + watermarks from memory row.
     * Messages are also position-filtered. Callers must not bypass this for MEMORY injection.
     */
    public String buildChatPrompt(
            ReadingBuddyPersona persona,
            String bookTitle,
            String author,
            int chapterIndex,
            String chapterTitle,
            int paragraphIndex,
            String storyContextBody,
            String memorySummary,
            Integer summaryMaxChapterIndex,
            Integer summaryMaxParagraphIndex,
            List<ReadingBuddyPositionedMessage> recentMessages,
            String userMessage) {
        int maxMsgs = Math.max(1, properties.getChat().getMaxContextMessages());
        List<ReadingBuddyPositionedMessage> filtered = filterMessagesByPosition(
                recentMessages, chapterIndex, paragraphIndex);
        String resolvedMemory = resolveMemorySummaryForPosition(
                memorySummary,
                summaryMaxChapterIndex,
                summaryMaxParagraphIndex,
                chapterIndex,
                paragraphIndex);
        return joinSections(
                buildSystemPrompt(persona, bookTitle, author, chapterIndex, chapterTitle, paragraphIndex),
                buildStoryContextSection(storyContextBody),
                buildMemorySection(resolvedMemory),
                buildConversationSection(filtered, maxMsgs),
                "Reader: " + blankTo(userMessage, "").trim() + "\n" + persona.displayName() + ":");
    }

    /**
     * Convenience when there is no stored summary yet (empty MEMORY).
     */
    public String buildChatPrompt(
            ReadingBuddyPersona persona,
            String bookTitle,
            String author,
            int chapterIndex,
            String chapterTitle,
            int paragraphIndex,
            String storyContextBody,
            List<ReadingBuddyPositionedMessage> recentMessages,
            String userMessage) {
        return buildChatPrompt(
                persona,
                bookTitle,
                author,
                chapterIndex,
                chapterTitle,
                paragraphIndex,
                storyContextBody,
                "",
                null,
                null,
                recentMessages,
                userMessage);
    }

    /**
     * Full chat prompt loading STORY CONTEXT for the given position.
     * Watermark fields are required so MEMORY omit cannot be skipped by callers.
     */
    public String buildChatPromptForPosition(
            ReadingBuddyPersona persona,
            String bookId,
            String bookTitle,
            String author,
            int chapterIndex,
            String chapterTitle,
            int paragraphIndex,
            String memorySummary,
            Integer summaryMaxChapterIndex,
            Integer summaryMaxParagraphIndex,
            List<ReadingBuddyPositionedMessage> recentMessages,
            String userMessage) {
        String story = loadStoryContext(bookId, chapterIndex, paragraphIndex);
        return buildChatPrompt(
                persona,
                bookTitle,
                author,
                chapterIndex,
                chapterTitle,
                paragraphIndex,
                story,
                memorySummary,
                summaryMaxChapterIndex,
                summaryMaxParagraphIndex,
                recentMessages,
                userMessage);
    }

    // --- Pure position helpers (unit-testable) ---

    /**
     * Lexicographic position compare: chapter first, then paragraph.
     * Returns negative if (c1,p1) &lt; (c2,p2), zero if equal, positive if greater.
     */
    public static int comparePosition(int chapter1, int paragraph1, int chapter2, int paragraph2) {
        if (chapter1 != chapter2) {
            return Integer.compare(chapter1, chapter2);
        }
        return Integer.compare(paragraph1, paragraph2);
    }

    /**
     * True when (chapter, paragraph) is at or before the reader's current position.
     */
    public static boolean isPositionAtOrBefore(
            int chapterIndex, int paragraphIndex, int readerChapterIndex, int readerParagraphIndex) {
        return comparePosition(chapterIndex, paragraphIndex, readerChapterIndex, readerParagraphIndex) <= 0;
    }

    /**
     * Exclude messages whose position is ahead of the reader (spoiler-safe injection).
     * Null messages are dropped. Order preserved.
     */
    public static List<ReadingBuddyPositionedMessage> filterMessagesByPosition(
            List<ReadingBuddyPositionedMessage> messages,
            int readerChapterIndex,
            int readerParagraphIndex) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ReadingBuddyPositionedMessage> out = new ArrayList<>();
        for (ReadingBuddyPositionedMessage msg : messages) {
            if (msg == null) {
                continue;
            }
            if (isPositionAtOrBefore(
                    msg.chapterIndex(), msg.paragraphIndex(), readerChapterIndex, readerParagraphIndex)) {
                out.add(msg);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Rolling-summary watermark rule: include summary only when the reader is at or ahead of
     * the summary watermark.
     * <ul>
     *   <li>Both watermarks null → treat as no watermark (include; summary may still be empty).</li>
     *   <li>Exactly one watermark null (partial) → fail closed (omit).</li>
     *   <li>Both set and reader strictly behind → omit; at or ahead → include.</li>
     * </ul>
     */
    public static boolean shouldIncludeSummary(
            Integer summaryMaxChapterIndex,
            Integer summaryMaxParagraphIndex,
            int readerChapterIndex,
            int readerParagraphIndex) {
        boolean chapterNull = summaryMaxChapterIndex == null;
        boolean paragraphNull = summaryMaxParagraphIndex == null;
        if (chapterNull && paragraphNull) {
            // No watermark stored: safe to include (summary may still be empty).
            return true;
        }
        if (chapterNull || paragraphNull) {
            // Partial watermark with non-empty summary would be unsafe — fail closed.
            return false;
        }
        return comparePosition(
                readerChapterIndex,
                readerParagraphIndex,
                summaryMaxChapterIndex,
                summaryMaxParagraphIndex) >= 0;
    }

    private static String conversationRoleLabel(String role) {
        if (role == null) {
            return "Buddy";
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "user" -> "Reader";
            case "buddy", "assistant" -> "Buddy";
            case "system" -> "System";
            default -> "Buddy";
        };
    }

    private static String joinSections(String... sections) {
        StringBuilder sb = new StringBuilder();
        for (String section : sections) {
            if (section == null || section.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(section.stripTrailing());
        }
        return sb.toString();
    }

    private static String blankTo(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
