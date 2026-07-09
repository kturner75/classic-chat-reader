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
    }

    @Test
    void historian_promptIncludesPlotBanAndPreferNoneBias() {
        ReadingBuddyPersona historian = catalog.findById(ReadingBuddyPersonaCatalog.HISTORIAN).orElseThrow();

        String system = promptBuilder.buildSystemPrompt(
                historian, "Frankenstein", "Mary Shelley", 2, "Chapter III", 5);
        String proactive = promptBuilder.buildProactivePrompt(
                historian,
                "Frankenstein",
                "Mary Shelley",
                2,
                "Chapter III",
                5,
                "I beheld the wretch.",
                "");

        String combined = system + "\n" + proactive;
        String lower = combined.toLowerCase();

        assertTrue(combined.contains("STORY BOUNDARY (CRITICAL)"));
        assertTrue(combined.contains("NON-PLOT CONTEXT"));
        assertTrue(lower.contains("plot"));
        assertTrue(lower.contains("never") || lower.contains("only use story context"));
        assertTrue(combined.contains("SPARSITY"));
        assertTrue(lower.contains("prefer none"));
        assertTrue(lower.contains("non-plot"));
        // Persona catalog voice still present
        assertTrue(historian.systemPrompt().toLowerCase().contains("non-plot"));
        assertTrue(combined.contains(historian.systemPrompt().trim().substring(0, 20))
                || combined.contains("The Archivist"));
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
                persona, "Book", "Author", 0, "Ch 1", 1, storyBody, "");

        assertTrue(prompt.contains("STORY CONTEXT"));
        assertTrue(prompt.contains("Only safe text."));
        assertTrue(prompt.contains("MEMORY:"));
        assertTrue(prompt.contains("(No memory yet.)"));
        assertTrue(prompt.contains("SPARSITY"));
        assertFalse(prompt.toLowerCase().contains("prefer none")); // not historian
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
                "",
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
                List.of(),
                "Ha?");

        assertTrue(prompt.contains("Safe passage."));
        assertTrue(prompt.contains("school-safe") || prompt.toLowerCase().contains("school-safe"));
    }

    @Test
    void comparePosition_isLexicographic() {
        assertTrue(ReadingBuddyPromptBuilder.comparePosition(1, 5, 2, 0) < 0);
        assertTrue(ReadingBuddyPromptBuilder.comparePosition(2, 0, 1, 99) > 0);
        assertEquals(0, ReadingBuddyPromptBuilder.comparePosition(3, 4, 3, 4));
        assertTrue(ReadingBuddyPromptBuilder.comparePosition(3, 1, 3, 2) < 0);
    }
}
