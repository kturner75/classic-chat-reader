package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import com.classicchatreader.service.llm.LlmProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.SocketException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingBuddyCommentServiceTest {

    @Mock
    private LlmProvider chatProvider;
    @Mock
    private ReadingBuddyTriggerPolicy triggerPolicy;
    @Mock
    private ReadingBuddyPromptBuilder promptBuilder;
    @Mock
    private ReadingBuddyMemoryService memoryService;
    @Mock
    private ReadingBuddyPreferenceService preferenceService;
    @Mock
    private ReadingBuddyMetricsService metricsService;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private ChapterRepository chapterRepository;
    @Mock
    private ParagraphRepository paragraphRepository;

    private ReadingBuddyProperties properties;
    private ReadingBuddyPersonaCatalog catalog;
    private ReadingBuddyCommentService commentService;

    @BeforeEach
    void setUp() {
        properties = new ReadingBuddyProperties();
        properties.getProactive().setMaxWords(60);
        properties.getMinCooldownMs().setRare(180_000L);
        catalog = new ReadingBuddyPersonaCatalog(properties);
        when(chatProvider.getProviderName()).thenReturn("mock");
        commentService = new ReadingBuddyCommentService(
                chatProvider,
                triggerPolicy,
                promptBuilder,
                memoryService,
                catalog,
                preferenceService,
                properties,
                metricsService,
                bookRepository,
                chapterRepository,
                paragraphRepository
        );
    }

    @Test
    void checkComment_cooldown_doesNotCallLlm() {
        stubBookAndPosition();
        when(preferenceService.getEffective("owner", "book-1"))
                .thenReturn(enabledPrefs());
        when(triggerPolicy.evaluate(any()))
                .thenReturn(new ReadingBuddyTriggerPolicy.TriggerDecision.Silence(
                        ReadingBuddyTriggerPolicy.SilenceReason.COOLDOWN, 120_000L));

        ReadingBuddyCommentService.CheckCommentResult result = commentService.checkComment(
                "owner", "book-1", "humorist", 3, 12, null);

        ReadingBuddyCommentService.CheckCommentResult.Silence silence =
                assertInstanceOf(ReadingBuddyCommentService.CheckCommentResult.Silence.class, result);
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.COOLDOWN, silence.reason());
        verify(chatProvider, never()).generate(anyString(), any());
        verify(memoryService, never()).persistProactiveComment(
                any(), any(), any(), any(), anyInt(), anyInt());
        verify(metricsService).recordCheckTotal();
        verify(metricsService).recordCheckSilence();
        verify(metricsService).recordCheckLatency(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void checkComment_suppressed_doesNotCallLlm() {
        stubBookAndPosition();
        when(preferenceService.getEffective("owner", "book-1"))
                .thenReturn(enabledPrefs());
        when(triggerPolicy.evaluate(any()))
                .thenReturn(new ReadingBuddyTriggerPolicy.TriggerDecision.Silence(
                        ReadingBuddyTriggerPolicy.SilenceReason.SUPPRESSED, 45 * 60_000L));

        ReadingBuddyCommentService.CheckCommentResult.Silence silence =
                assertInstanceOf(ReadingBuddyCommentService.CheckCommentResult.Silence.class,
                        commentService.checkComment("owner", "book-1", "humorist", 3, 12, null));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.SUPPRESSED, silence.reason());
        verify(chatProvider, never()).generate(anyString(), any());
    }

    @Test
    void checkComment_eligibleComment_persistsAndReturns() {
        stubBookAndPosition();
        when(preferenceService.getEffective("owner", "book-1"))
                .thenReturn(enabledPrefs());
        when(triggerPolicy.evaluate(any()))
                .thenReturn(new ReadingBuddyTriggerPolicy.TriggerDecision.Eligible(180_000L));
        when(memoryService.getMemorySnapshot("owner", "book-1", "humorist"))
                .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot("", null, null, 0, null));
        when(promptBuilder.buildProactivePromptForPosition(
                any(), eq("book-1"), any(), any(), eq(3), any(), eq(12), any(), any(), any()))
                .thenReturn("PROMPT");
        when(chatProvider.generate(eq("PROMPT"), any(LlmOptions.class)))
                .thenReturn("COMMENT: Darcy really said she is tolerable.");
        ReadingBuddyMessageEntity saved = new ReadingBuddyMessageEntity();
        saved.setId("proactive-1");
        saved.setContent("Darcy really said she is tolerable.");
        when(memoryService.persistProactiveComment(
                eq("owner"), eq("book-1"), eq("humorist"),
                eq("Darcy really said she is tolerable."), eq(3), eq(12)))
                .thenReturn(ReadingBuddyMemoryService.ProactivePersistResult.inserted(saved));

        ReadingBuddyCommentService.CheckCommentResult.Comment comment =
                assertInstanceOf(ReadingBuddyCommentService.CheckCommentResult.Comment.class,
                        commentService.checkComment("owner", "book-1", "humorist", 3, 12,
                                new ReadingBuddyCommentService.ClientHint(9, 1200L)));

        assertEquals("COMMENT", comment.action());
        assertEquals("proactive-1", comment.messageId());
        assertEquals("Darcy really said she is tolerable.", comment.text());
        assertEquals("humorist", comment.personaId());
        assertEquals("/images/buddies/humorist.png", comment.portraitUrl());
        verify(metricsService).recordCheckComment();
    }

    @Test
    void checkComment_providerError_returnsProviderErrorNotDecidedNone() {
        stubBookAndPosition();
        when(preferenceService.getEffective("owner", "book-1"))
                .thenReturn(enabledPrefs());
        when(triggerPolicy.evaluate(any()))
                .thenReturn(new ReadingBuddyTriggerPolicy.TriggerDecision.Eligible(180_000L));
        when(memoryService.getMemorySnapshot(any(), any(), any()))
                .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot("", null, null, 0, null));
        when(promptBuilder.buildProactivePromptForPosition(
                any(), any(), any(), any(), anyInt(), any(), anyInt(), any(), any(), any()))
                .thenReturn("PROMPT");
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenThrow(new LlmProviderException(
                        "Failed to generate response from OpenAI",
                        new SocketException("Connection reset")));

        ReadingBuddyCommentService.CheckCommentResult.Silence silence =
                assertInstanceOf(ReadingBuddyCommentService.CheckCommentResult.Silence.class,
                        commentService.checkComment("owner", "book-1", "humorist", 3, 12, null));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.PROVIDER_ERROR, silence.reason());
        verify(metricsService).recordCheckFailed();
        verify(memoryService, never()).persistProactiveComment(
                any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void checkComment_raceExisting_doesNotRecordCheckComment() {
        stubBookAndPosition();
        when(preferenceService.getEffective("owner", "book-1"))
                .thenReturn(enabledPrefs());
        when(triggerPolicy.evaluate(any()))
                .thenReturn(new ReadingBuddyTriggerPolicy.TriggerDecision.Eligible(180_000L));
        when(memoryService.getMemorySnapshot(any(), any(), any()))
                .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot("", null, null, 0, null));
        when(promptBuilder.buildProactivePromptForPosition(
                any(), any(), any(), any(), anyInt(), any(), anyInt(), any(), any(), any()))
                .thenReturn("PROMPT");
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("COMMENT: Loser text after race.");
        ReadingBuddyMessageEntity winner = new ReadingBuddyMessageEntity();
        winner.setId("winner-1");
        winner.setContent("First comment wins");
        when(memoryService.persistProactiveComment(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(ReadingBuddyMemoryService.ProactivePersistResult.existing(winner));

        ReadingBuddyCommentService.CheckCommentResult.Comment comment =
                assertInstanceOf(ReadingBuddyCommentService.CheckCommentResult.Comment.class,
                        commentService.checkComment("owner", "book-1", "humorist", 3, 12, null));
        assertEquals("winner-1", comment.messageId());
        assertEquals("First comment wins", comment.text());
        verify(metricsService, never()).recordCheckComment();
        verify(metricsService).recordCheckSilence();
    }

    @Test
    void checkComment_llmNone_returnsDecidedNoneWithoutPersist() {
        stubBookAndPosition();
        when(preferenceService.getEffective("owner", "book-1"))
                .thenReturn(enabledPrefs());
        when(triggerPolicy.evaluate(any()))
                .thenReturn(new ReadingBuddyTriggerPolicy.TriggerDecision.Eligible(180_000L));
        when(memoryService.getMemorySnapshot(any(), any(), any()))
                .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot("", null, null, 0, null));
        when(promptBuilder.buildProactivePromptForPosition(
                any(), any(), any(), any(), anyInt(), any(), anyInt(), any(), any(), any()))
                .thenReturn("PROMPT");
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("NONE: transitional passage");

        ReadingBuddyCommentService.CheckCommentResult.Silence silence =
                assertInstanceOf(ReadingBuddyCommentService.CheckCommentResult.Silence.class,
                        commentService.checkComment("owner", "book-1", "humorist", 3, 12, null));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.DECIDED_NONE, silence.reason());
        verify(memoryService, never()).persistProactiveComment(
                any(), any(), any(), any(), anyInt(), anyInt());
        verify(metricsService).recordCheckSilence();
    }

    @Test
    void checkComment_invalidGrammar_failClosedAsNone() {
        stubBookAndPosition();
        when(preferenceService.getEffective("owner", "book-1"))
                .thenReturn(enabledPrefs());
        when(triggerPolicy.evaluate(any()))
                .thenReturn(new ReadingBuddyTriggerPolicy.TriggerDecision.Eligible(180_000L));
        when(memoryService.getMemorySnapshot(any(), any(), any()))
                .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot("", null, null, 0, null));
        when(promptBuilder.buildProactivePromptForPosition(
                any(), any(), any(), any(), anyInt(), any(), anyInt(), any(), any(), any()))
                .thenReturn("PROMPT");
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("Sure, here's a witty remark without grammar!");

        ReadingBuddyCommentService.CheckCommentResult.Silence silence =
                assertInstanceOf(ReadingBuddyCommentService.CheckCommentResult.Silence.class,
                        commentService.checkComment("owner", "book-1", "humorist", 3, 12, null));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.DECIDED_NONE, silence.reason());
        verify(memoryService, never()).persistProactiveComment(
                any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void parseLlmDecision_commentAndNoneAmbiguous_isNone() {
        assertEquals(
                ReadingBuddyCommentService.ParsedAction.NONE,
                ReadingBuddyCommentService.parseLlmDecision("COMMENT: hi\nNONE: nah").action());
    }

    @Test
    void hardTruncateWords_respectsMaxAndSentenceBoundary() {
        String longText = "One two three four five. Six seven eight nine ten eleven.";
        String truncated = ReadingBuddyCommentService.hardTruncateWords(longText, 6);
        assertEquals("One two three four five.", truncated);

        String noSentence = "alpha beta gamma delta epsilon zeta eta";
        assertEquals("alpha beta gamma", ReadingBuddyCommentService.hardTruncateWords(noSentence, 3));
        assertTrue(ReadingBuddyCommentService.hardTruncateWords("", 10).isEmpty());
    }

    @Test
    void hardTruncateWords_skipsShortAbbreviations() {
        // Would otherwise cut at "Dr." if abbreviation detection were absent.
        String text = "Dr. Smith said something witty about Darcy today evening night.";
        String truncated = ReadingBuddyCommentService.hardTruncateWords(text, 6);
        assertTrue(truncated.length() > 4, truncated);
        assertFalse(truncated.equals("Dr."), truncated);
        assertTrue(ReadingBuddyCommentService.isLikelyAbbreviation("Dr. Smith", 2));
    }

    private void stubBookAndPosition() {
        BookEntity book = new BookEntity("Pride and Prejudice", "Jane Austen", "manual");
        book.setId("book-1");
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        ChapterEntity chapter = new ChapterEntity(3, "Chapter IV");
        chapter.setId("ch-3");
        when(chapterRepository.findByBookIdAndChapterIndex("book-1", 3)).thenReturn(Optional.of(chapter));
        when(paragraphRepository.existsByChapterIdAndParagraphIndex("ch-3", 12)).thenReturn(true);
    }

    private static ReadingBuddyPreferenceService.EffectivePreferences enabledPrefs() {
        return new ReadingBuddyPreferenceService.EffectivePreferences(
                true, "rare", "humorist", "humorist", "global", null, "book-1");
    }
}
