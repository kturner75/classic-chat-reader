package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.model.ReadingBuddyPersona;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingBuddyPromptBuilderTest {

    @Mock
    private ReadingBuddyStoryContextLoader storyContextLoader;

    private ReadingBuddyProperties properties;
    private ReadingBuddyPersonaCatalog catalog;
    private ReadingBuddyPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        properties = new ReadingBuddyProperties();
        catalog = new ReadingBuddyPersonaCatalog(properties);
        promptBuilder = new ReadingBuddyPromptBuilder(storyContextLoader, properties);
    }

    @Test
    void buildSystemPrompt_includesStoryBoundaryAndCommentaryStyle() {
        ReadingBuddyPersona close = catalog.findById(ReadingBuddyPersonaCatalog.CLOSE_READER).orElseThrow();

        String prompt = promptBuilder.buildSystemPrompt(
                close, "Pride and Prejudice", "Jane Austen", 3, "Chapter IV", 12);

        assertTrue(prompt.contains("STORY BOUNDARY (CRITICAL)"));
        assertTrue(prompt.contains("STORY CONTEXT and MEMORY"));
        assertTrue(prompt.contains("COMMENTARY STYLE"));
        assertTrue(prompt.contains("chapter index 3"));
        assertTrue(prompt.contains("paragraph index 12"));
        assertTrue(prompt.contains("Pride and Prejudice"));
        assertTrue(prompt.contains("The Marginalian"));
        assertTrue(prompt.contains("NON-PLOT CONTEXT"));
        // Catalog voice is persona-only (no second full STORY BOUNDARY block)
        assertEquals(1, countOccurrences(prompt, "STORY BOUNDARY (CRITICAL)"));
    }

    @Test
    void historian_promptIncludesPlotBanAndPreferNoneBias() {
        ReadingBuddyPersona historian = catalog.findById(ReadingBuddyPersonaCatalog.HISTORIAN).orElseThrow();

        String proactive = promptBuilder.buildProactivePrompt(
                historian,
                "Frankenstein",
                "Mary Shelley",
                2,
                "Chapter III",
                5,
                "I beheld the wretch.");

        String lower = proactive.toLowerCase();

        assertTrue(proactive.contains("STORY BOUNDARY (CRITICAL)"));
        assertTrue(proactive.contains("NON-PLOT CONTEXT"));
        assertTrue(lower.contains("plot"));
        assertTrue(lower.contains("never") || lower.contains("only use story context"));
        assertTrue(proactive.contains("SPARSITY"));
        assertTrue(lower.contains("prefer none"));
        assertTrue(lower.contains("non-plot"));
        assertTrue(historian.systemPrompt().toLowerCase().contains("non-plot"));
        assertTrue(proactive.contains("The Archivist"));
        // Builder is the single authoritative boundary (catalog is persona-voice only).
        assertEquals(1, countOccurrences(proactive, "STORY BOUNDARY (CRITICAL)"));
    }

    @Test
    void humorist_promptIncludesSchoolSafePolicyFromCatalog() {
        ReadingBuddyPersona humorist = catalog.findById(ReadingBuddyPersonaCatalog.HUMORIST).orElseThrow();

        String prompt = promptBuilder.buildSystemPrompt(
                humorist, "Emma", "Jane Austen", 0, "Chapter I", 0);

        String lower = prompt.toLowerCase();
        assertTrue(lower.contains("school-safe"));
        assertTrue(lower.contains("punch down") || lower.contains("protected") || lower.contains("trauma"));
        assertTrue(prompt.contains("PERSONA INSTRUCTIONS"));
    }

    @Test
    void buildProactiveTaskPrompt_includesSparsityGrammar() {
        String task = promptBuilder.buildProactiveTaskPrompt();
        assertTrue(task.contains("SPARSITY"));
        assertTrue(task.contains("COMMENT:"));
        assertTrue(task.contains("NONE:"));
    }

    @Test
    void buildProactivePrompt_assemblesSectionsWithoutFutureLeakInStoryBody() {
        ReadingBuddyPersona persona = catalog.findById(ReadingBuddyPersonaCatalog.ENCOURAGER).orElseThrow();
        String storyBody = "[Current paragraph 1]:\nOnly safe text.";

        String prompt = promptBuilder.buildProactivePrompt(
                persona, "Book", "Author", 0, "Ch 1", 1, storyBody);

        assertTrue(prompt.contains("STORY CONTEXT"));
        assertTrue(prompt.contains("Only safe text."));
        assertTrue(prompt.contains("MEMORY:"));
        assertTrue(prompt.contains("(No memory yet.)"));
        assertTrue(prompt.contains("SPARSITY"));
        assertFalse(prompt.toLowerCase().contains("prefer none")); // not historian
    }

    @Test
    void buildChatPrompt_omitsBehindWatermarkSummaryFromMemory() {
        ReadingBuddyPersona persona = catalog.findById(ReadingBuddyPersonaCatalog.CLOSE_READER).orElseThrow();
        String spoilerSummary = "Elizabeth marries Darcy after many misunderstandings.";

        String prompt = promptBuilder.buildChatPrompt(
                persona,
                "Pride and Prejudice",
                "Jane Austen",
                3,
                "Chapter IV",
                2,
                "She was not handsome enough to tempt me.",
                spoilerSummary,
                10,
                0,
                List.of(),
                "Does she marry him?");

        assertTrue(prompt.contains("MEMORY:"));
        assertTrue(prompt.contains("(No memory yet.)"));
        assertFalse(prompt.contains("Elizabeth marries Darcy"));
        assertFalse(prompt.contains(spoilerSummary));
    }

    @Test
    void buildProactivePrompt_omitsBehindWatermarkSummaryFromMemory() {
        ReadingBuddyPersona persona = catalog.findById(ReadingBuddyPersonaCatalog.HISTORIAN).orElseThrow();
        String spoilerSummary = "The creature kills William and frames Justine.";

        String prompt = promptBuilder.buildProactivePrompt(
                persona,
                "Frankenstein",
                "Mary Shelley",
                2,
                "Chapter III",
                5,
                "I beheld the wretch.",
                spoilerSummary,
                10,
                0);

        assertFalse(prompt.contains("kills William"));
        assertFalse(prompt.contains(spoilerSummary));
        assertTrue(prompt.contains("(No memory yet.)"));
    }

    @Test
    void buildChatPrompt_includesSummaryWhenReaderAtOrAheadOfWatermark() {
        ReadingBuddyPersona persona = catalog.findById(ReadingBuddyPersonaCatalog.ENCOURAGER).orElseThrow();
        String summary = "Reader liked the rain scene.";

        String prompt = promptBuilder.buildChatPrompt(
                persona,
                "Book",
                "Author",
                10,
                "Ch XI",
                0,
                "Rain fell.",
                summary,
                10,
                0,
                List.of(),
                "Hello");

        assertTrue(prompt.contains(summary));
        assertFalse(prompt.contains("(No memory yet.)"));
    }

    @Test
    void filterMessagesByPosition_excludesFutureMessages() {
        List<ReadingBuddyPositionedMessage> messages = List.of(
                new ReadingBuddyPositionedMessage("buddy", "Early note", "proactive", 1, 0),
                new ReadingBuddyPositionedMessage("user", "What next?", "chat", 1, 2),
                new ReadingBuddyPositionedMessage("buddy", "Spoiler from ch10", "chat", 10, 0),
                new ReadingBuddyPositionedMessage("user", "At ch3", "chat", 3, 5)
        );

        List<ReadingBuddyPositionedMessage> filtered =
                ReadingBuddyPromptBuilder.filterMessagesByPosition(messages, 3, 2);

        assertEquals(2, filtered.size());
        assertEquals("Early note", filtered.get(0).content());
        assertEquals("What next?", filtered.get(1).content());
        assertTrue(filtered.stream().noneMatch(m -> m.content().contains("Spoiler")));
        assertTrue(filtered.stream().noneMatch(m -> m.content().contains("At ch3")));
    }

    @Test
    void filterMessagesByPosition_includesSamePosition() {
        ReadingBuddyPositionedMessage same =
                new ReadingBuddyPositionedMessage("buddy", "Here", "proactive", 2, 4);
        List<ReadingBuddyPositionedMessage> filtered =
                ReadingBuddyPromptBuilder.filterMessagesByPosition(List.of(same), 2, 4);
        assertEquals(1, filtered.size());
    }

    @Test
    void shouldIncludeSummary_omitsWhenReaderBehindWatermark() {
        assertFalse(ReadingBuddyPromptBuilder.shouldIncludeSummary(10, 0, 3, 5));
        assertTrue(ReadingBuddyPromptBuilder.shouldIncludeSummary(10, 0, 10, 0));
        assertTrue(ReadingBuddyPromptBuilder.shouldIncludeSummary(10, 0, 11, 0));
        assertTrue(ReadingBuddyPromptBuilder.shouldIncludeSummary(null, null, 0, 0));
    }

    @Test
    void shouldIncludeSummary_partialWatermark_failsClosed() {
        assertFalse(ReadingBuddyPromptBuilder.shouldIncludeSummary(10, null, 3, 0));
        assertFalse(ReadingBuddyPromptBuilder.shouldIncludeSummary(null, 2, 11, 0));
        assertFalse(ReadingBuddyPromptBuilder.shouldIncludeSummary(10, null, 10, 0));
    }

    @Test
    void resolveMemorySummaryForPosition_omitsOnRewindBehindWatermark() {
        String summary = "They discussed Darcy at the ball.";
        assertEquals(
                "",
                promptBuilder.resolveMemorySummaryForPosition(summary, 10, 2, 3, 0));
        assertEquals(
                summary,
                promptBuilder.resolveMemorySummaryForPosition(summary, 10, 2, 10, 2));
        assertEquals(
                "",
                promptBuilder.resolveMemorySummaryForPosition("", 1, 0, 5, 0));
        assertEquals(
                "",
                promptBuilder.resolveMemorySummaryForPosition(summary, 10, null, 12, 0));
    }

    @Test
    void buildChatPrompt_filtersMessagesAndIncludesUserTurn() {
        ReadingBuddyPersona persona = catalog.findById(ReadingBuddyPersonaCatalog.CLOSE_READER).orElseThrow();
        List<ReadingBuddyPositionedMessage> history = List.of(
                new ReadingBuddyPositionedMessage("buddy", "Nice diction.", "proactive", 1, 1),
                new ReadingBuddyPositionedMessage("buddy", "Future spoiler chat", "chat", 8, 0),
                new ReadingBuddyPositionedMessage("user", "Why that word?", "chat", 1, 2)
        );

        String prompt = promptBuilder.buildChatPrompt(
                persona,
                "Moby-Dick",
                "Herman Melville",
                1,
                "Chapter II",
                3,
                "Call me Ishmael.",
                history,
                "What does this line suggest?");

        assertTrue(prompt.contains("Nice diction."));
        assertTrue(prompt.contains("Why that word?"));
        assertFalse(prompt.contains("Future spoiler chat"));
        assertTrue(prompt.contains("Reader: What does this line suggest?"));
        assertTrue(prompt.contains("The Marginalian:"));
        assertTrue(prompt.contains("STORY CONTEXT"));
        assertTrue(prompt.contains("Call me Ishmael."));
    }

    @Test
    void buildChatPromptForPosition_loadsStoryContext() {
        ReadingBuddyPersona persona = catalog.findById(ReadingBuddyPersonaCatalog.HUMORIST).orElseThrow();
        when(storyContextLoader.loadStoryContext("book-x", 0, 2))
                .thenReturn("[Current paragraph 2]:\nSafe passage.");

        String prompt = promptBuilder.buildChatPromptForPosition(
                persona,
                "book-x",
                "Title",
                "Author",
                0,
                "Ch1",
                2,
                "",
                null,
                null,
                List.of(),
                "Ha?");

        assertTrue(prompt.contains("Safe passage."));
        assertTrue(prompt.toLowerCase().contains("school-safe"));
    }

    @Test
    void comparePosition_isLexicographic() {
        assertTrue(ReadingBuddyPromptBuilder.comparePosition(1, 5, 2, 0) < 0);
        assertTrue(ReadingBuddyPromptBuilder.comparePosition(2, 0, 1, 99) > 0);
        assertEquals(0, ReadingBuddyPromptBuilder.comparePosition(3, 4, 3, 4));
        assertTrue(ReadingBuddyPromptBuilder.comparePosition(3, 1, 3, 2) < 0);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }
}
