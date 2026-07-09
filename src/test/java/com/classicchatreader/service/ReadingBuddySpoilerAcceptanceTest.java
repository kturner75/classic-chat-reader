package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.model.ReadingBuddyPersona;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Required spoiler acceptance bar before production {@code reading-buddy.enabled=true}.
 * <p>
 * Design doc criteria (stubbed {@link LlmProvider}):
 * <ol>
 *   <li>Mid-book Pride and Prejudice: marriage question → deflection / no confirmation</li>
 *   <li>Mid-book Frankenstein: creature fate / who dies → no future reveal</li>
 *   <li>Historian proactive prompt includes prefer NONE and plot-ban language</li>
 *   <li>Message at chapter 10 not injected when reader at chapter 3</li>
 *   <li>History marks future-relative messages not visible</li>
 *   <li>Summary watermark ch10 fully omitted from prompt at ch3</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ReadingBuddySpoilerAcceptanceTest {

    private static final int MID_CHAPTER = 3;
    private static final int MID_PARAGRAPH = 5;
    private static final String SAFE_PNP =
            "She was not handsome enough to tempt me, and I am in no humour at present.";
    private static final String SAFE_FRANKENSTEIN =
            "I beheld the wretch—the miserable monster whom I had created.";
    private static final String PNP_SPOILER_SUMMARY =
            "Elizabeth marries Darcy after many misunderstandings and the Wickham scandal.";
    private static final String FRANKENSTEIN_SPOILER_SUMMARY =
            "The creature kills William and frames Justine; later Victor and Elizabeth die.";
    private static final String CH10_MESSAGE = "Spoiler chat from chapter 10 about the wedding.";

    @Mock
    private LlmProvider chatProvider;

    @Mock
    private ReadingBuddyStoryContextLoader storyContextLoader;

    @Mock
    private ReadingBuddyMemoryService memoryService;

    @Mock
    private ReadingBuddyMetricsService metricsService;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ChapterRepository chapterRepository;

    private ReadingBuddyProperties properties;
    private ReadingBuddyPersonaCatalog catalog;
    private ReadingBuddyPromptBuilder promptBuilder;
    private ReadingBuddyChatService chatService;

    @BeforeEach
    void setUp() {
        properties = new ReadingBuddyProperties();
        catalog = new ReadingBuddyPersonaCatalog(properties);
        promptBuilder = new ReadingBuddyPromptBuilder(storyContextLoader, properties);
        when(chatProvider.getProviderName()).thenReturn("stub");
        chatService = new ReadingBuddyChatService(
                chatProvider,
                promptBuilder,
                memoryService,
                catalog,
                properties,
                metricsService,
                bookRepository,
                chapterRepository
        );
    }

    @Nested
    @DisplayName("1–2: Mid-book plot questions (stubbed LlmProvider)")
    class MidBookPlotQuestions {

        @Test
        @DisplayName("Pride and Prejudice mid-book: marriage question deflects; no confirmation; no future memory")
        void prideAndPrejudice_doesElizabethMarryDarcy_deflectsWithoutConfirmation() {
            BookEntity book = book("book-pnp", "Pride and Prejudice", "Jane Austen");
            stubBookAndChapter(book, MID_CHAPTER, "Chapter IV");
            when(storyContextLoader.loadStoryContext(eq("book-pnp"), eq(MID_CHAPTER), eq(MID_PARAGRAPH)))
                    .thenReturn("[Current paragraph " + MID_PARAGRAPH + "]:\n" + SAFE_PNP);

            // Future-relative memory must not enter the prompt at mid-book position.
            when(memoryService.loadRecentMessagesForPrompt("owner-A", "book-pnp", "close_reader", MID_CHAPTER, MID_PARAGRAPH))
                    .thenReturn(List.of(
                            new ReadingBuddyPositionedMessage("user", "Who is Mr Darcy?", "chat", 2, 0),
                            new ReadingBuddyPositionedMessage("buddy", CH10_MESSAGE, "chat", 10, 0)
                    ));
            when(memoryService.getMemorySnapshot("owner-A", "book-pnp", "close_reader"))
                    .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot(
                            PNP_SPOILER_SUMMARY, 10, 0, 1, null));

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            when(chatProvider.generate(anyString(), any(LlmOptions.class))).thenAnswer(invocation -> {
                String prompt = invocation.getArgument(0);
                capturedPrompt.set(prompt);
                // Stub provider respects STORY BOUNDARY in the assembled prompt.
                return "I only know what you've read so far — nothing later in the novel has been revealed yet.";
            });

            when(memoryService.persistChatTurn(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenAnswer(invocation -> {
                        String user = invocation.getArgument(3);
                        String buddy = invocation.getArgument(4);
                        return new ReadingBuddyMemoryService.ChatTurn(
                                message("u1", "user", user),
                                message("b1", "buddy", buddy));
                    });

            ReadingBuddyChatService.ChatResult result = chatService.chat(
                    "owner-A",
                    "book-pnp",
                    "close_reader",
                    "Does Elizabeth marry Darcy?",
                    MID_CHAPTER,
                    MID_PARAGRAPH);

            String prompt = capturedPrompt.get();
            assertTrue(prompt != null && !prompt.isBlank(), "prompt should be sent to LlmProvider");
            assertTrue(prompt.contains("STORY BOUNDARY (CRITICAL)"));
            assertTrue(prompt.toLowerCase(Locale.ROOT).contains("deflect"));
            assertTrue(prompt.contains(SAFE_PNP));
            assertTrue(prompt.contains("Does Elizabeth marry Darcy?"));
            // Spoiler gates: ch10 summary + ch10 message must not appear at ch3.
            assertFalse(prompt.contains(PNP_SPOILER_SUMMARY));
            assertFalse(prompt.contains("Elizabeth marries Darcy"));
            assertFalse(prompt.contains(CH10_MESSAGE));
            assertFalse(prompt.toLowerCase(Locale.ROOT).contains("wickham scandal"));

            String reply = result.response().toLowerCase(Locale.ROOT);
            assertFalse(reply.matches("(?s).*\\byes\\b.*marry.*"), "must not confirm the marriage");
            assertFalse(reply.contains("they marry") || reply.contains("she marries"));
            assertTrue(
                    reply.contains("read so far")
                            || reply.contains("nothing later")
                            || reply.contains("not been revealed")
                            || reply.contains("don't know yet")
                            || reply.contains("only know"),
                    "expected deflection language in reply: " + result.response());
        }

        @Test
        @DisplayName("Frankenstein mid-book: creature fate / who dies → no future reveal in prompt or reply")
        void frankenstein_creatureFate_noFutureReveal() {
            BookEntity book = book("book-frank", "Frankenstein", "Mary Shelley");
            stubBookAndChapter(book, MID_CHAPTER, "Chapter III");
            when(storyContextLoader.loadStoryContext(eq("book-frank"), eq(MID_CHAPTER), eq(MID_PARAGRAPH)))
                    .thenReturn("[Current paragraph " + MID_PARAGRAPH + "]:\n" + SAFE_FRANKENSTEIN);

            when(memoryService.loadRecentMessagesForPrompt("owner-A", "book-frank", "historian", MID_CHAPTER, MID_PARAGRAPH))
                    .thenReturn(List.of(
                            new ReadingBuddyPositionedMessage(
                                    "buddy", "The creature kills everyone later.", "chat", 10, 2)
                    ));
            when(memoryService.getMemorySnapshot("owner-A", "book-frank", "historian"))
                    .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot(
                            FRANKENSTEIN_SPOILER_SUMMARY, 10, 0, 2, null));

            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            when(chatProvider.generate(anyString(), any(LlmOptions.class))).thenAnswer(invocation -> {
                String prompt = invocation.getArgument(0);
                capturedPrompt.set(prompt);
                return "From what is on the page so far, I cannot say who dies later — that would be ahead of your reading.";
            });

            when(memoryService.persistChatTurn(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenAnswer(invocation -> new ReadingBuddyMemoryService.ChatTurn(
                            message("u2", "user", invocation.getArgument(3)),
                            message("b2", "buddy", invocation.getArgument(4))));

            ReadingBuddyChatService.ChatResult result = chatService.chat(
                    "owner-A",
                    "book-frank",
                    "historian",
                    "What happens to the creature? Who dies?",
                    MID_CHAPTER,
                    MID_PARAGRAPH);

            String prompt = capturedPrompt.get();
            assertTrue(prompt != null && !prompt.isBlank());
            assertTrue(prompt.contains("STORY BOUNDARY (CRITICAL)"));
            assertTrue(prompt.contains(SAFE_FRANKENSTEIN));
            assertFalse(prompt.contains(FRANKENSTEIN_SPOILER_SUMMARY));
            assertFalse(prompt.contains("kills William"));
            assertFalse(prompt.contains("frames Justine"));
            assertFalse(prompt.contains("The creature kills everyone later."));
            assertFalse(prompt.toLowerCase(Locale.ROOT).contains("elizabeth die"));

            String reply = result.response().toLowerCase(Locale.ROOT);
            assertFalse(reply.contains("kills william") || reply.contains("frames justine"));
            assertFalse(reply.contains("victor dies") || reply.contains("elizabeth dies"));
            assertTrue(
                    reply.contains("so far")
                            || reply.contains("ahead of your reading")
                            || reply.contains("cannot say")
                            || reply.contains("on the page"),
                    "expected no-future-reveal deflection: " + result.response());
        }
    }

    @Nested
    @DisplayName("3: Historian proactive prefer-NONE + plot ban")
    class HistorianProactivePrompt {

        @Test
        void historian_proactivePrompt_includesPreferNoneAndPlotBanLanguage() {
            ReadingBuddyPersona historian = catalog.findById(ReadingBuddyPersonaCatalog.HISTORIAN).orElseThrow();

            String proactive = promptBuilder.buildProactivePrompt(
                    historian,
                    "Frankenstein",
                    "Mary Shelley",
                    MID_CHAPTER,
                    "Chapter III",
                    MID_PARAGRAPH,
                    SAFE_FRANKENSTEIN);

            String lower = proactive.toLowerCase(Locale.ROOT);
            assertTrue(proactive.contains("STORY BOUNDARY (CRITICAL)"));
            assertTrue(proactive.contains("NON-PLOT CONTEXT") || lower.contains("non-plot"));
            assertTrue(lower.contains("prefer none"), "historian bias should prefer NONE: " + proactive);
            assertTrue(
                    lower.contains("plot") && (lower.contains("never") || lower.contains("ban")
                            || lower.contains("only use story context")
                            || lower.contains("never use outside knowledge")),
                    "expected plot-ban language: " + proactive);
            assertTrue(proactive.contains("SPARSITY") || lower.contains("none:"));
            assertTrue(proactive.contains("The Archivist"));
        }
    }

    @Nested
    @DisplayName("4: Future messages not injected at earlier position")
    class FutureMessageInjection {

        @Test
        void messageAtChapter10_notInjectedWhenReaderAtChapter3() {
            List<ReadingBuddyPositionedMessage> history = List.of(
                    new ReadingBuddyPositionedMessage("buddy", "Safe early note", "proactive", 1, 0),
                    new ReadingBuddyPositionedMessage("buddy", CH10_MESSAGE, "chat", 10, 0),
                    new ReadingBuddyPositionedMessage("user", "Question at ch3", "chat", 3, 2)
            );

            List<ReadingBuddyPositionedMessage> filtered =
                    ReadingBuddyPromptBuilder.filterMessagesByPosition(history, 3, 0);

            assertTrue(filtered.stream().anyMatch(m -> m.content().contains("Safe early note")));
            assertTrue(filtered.stream().noneMatch(m -> m.content().contains(CH10_MESSAGE)));
            assertTrue(filtered.stream().noneMatch(m -> m.content().contains("Question at ch3")),
                    "same-chapter later paragraph should also be excluded at paragraph 0");

            ReadingBuddyPersona persona = catalog.findById(ReadingBuddyPersonaCatalog.CLOSE_READER).orElseThrow();
            String prompt = promptBuilder.buildChatPrompt(
                    persona,
                    "Pride and Prejudice",
                    "Jane Austen",
                    3,
                    "Chapter IV",
                    0,
                    SAFE_PNP,
                    history,
                    "What happens later?");

            assertFalse(prompt.contains(CH10_MESSAGE));
            assertTrue(prompt.contains("Safe early note"));
        }
    }

    @Nested
    @DisplayName("5: History marks future-relative messages not visible")
    class HistoryVisibility {

        @Test
        void history_marksFutureRelativeMessagesNotVisible() {
            ReadingBuddyMessageRepository messageRepository = org.mockito.Mockito.mock(
                    ReadingBuddyMessageRepository.class);
            ReadingBuddyMemoryRepository memoryRepository = org.mockito.Mockito.mock(
                    ReadingBuddyMemoryRepository.class);

            ReadingBuddyMessageEntity visible = messageAt("m1", "visible early", 1, 0);
            ReadingBuddyMessageEntity hidden = messageAt("m2", "future relative", 10, 0);

            // History with includeHidden loads newest-first page, then reorders chrono.
            when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtDesc(
                    eq("owner-A"), eq("book-1"), eq("humorist"), any(Pageable.class)))
                    .thenReturn(List.of(hidden, visible));

            ReadingBuddyMemoryService memory = new ReadingBuddyMemoryService(
                    messageRepository,
                    memoryRepository,
                    properties,
                    chatProvider,
                    metricsService,
                    new ImmediateTxManager());

            ReadingBuddyMemoryService.HistoryResult result = memory.getHistory(
                    "owner-A", "book-1", "humorist", 50, 3, 0, true);

            assertTrue(result.messages().size() >= 2);
            ReadingBuddyMemoryService.HistoryMessage early = result.messages().stream()
                    .filter(m -> "visible early".equals(m.content()))
                    .findFirst()
                    .orElseThrow();
            ReadingBuddyMemoryService.HistoryMessage future = result.messages().stream()
                    .filter(m -> "future relative".equals(m.content()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(early.visibleAtPosition(), "ch1 should be visible at ch3");
            assertFalse(future.visibleAtPosition(), "ch10 should not be visible at ch3");
        }
    }

    @Nested
    @DisplayName("6: Summary watermark ch10 fully omitted at ch3")
    class SummaryWatermarkOmit {

        @Test
        void summaryWatermarkChapter10_fullyOmittedFromPromptAtChapter3() {
            ReadingBuddyPersona persona = catalog.findById(ReadingBuddyPersonaCatalog.HISTORIAN).orElseThrow();
            String summary = "They discussed the Netherfield ball and Darcy's first proposal.";

            String prompt = promptBuilder.buildChatPrompt(
                    persona,
                    "Pride and Prejudice",
                    "Jane Austen",
                    3,
                    "Chapter IV",
                    0,
                    SAFE_PNP,
                    summary,
                    10,
                    0,
                    List.of(new ReadingBuddyPositionedMessage(
                            "buddy", "Future relative chat", "chat", 10, 0)),
                    "What happens later?");

            assertFalse(prompt.contains(summary));
            assertFalse(prompt.contains("Netherfield"));
            assertFalse(prompt.contains("first proposal"));
            assertFalse(prompt.contains("Future relative chat"));
            assertTrue(prompt.contains("(No memory yet.)"));
            assertTrue(prompt.contains(SAFE_PNP));

            // Chat path with stubbed provider also omits via buildChatPromptForPosition.
            BookEntity book = book("book-pnp", "Pride and Prejudice", "Jane Austen");
            stubBookAndChapter(book, 3, "Chapter IV");
            when(storyContextLoader.loadStoryContext(eq("book-pnp"), eq(3), eq(0)))
                    .thenReturn(SAFE_PNP);
            when(memoryService.loadRecentMessagesForPrompt("owner-A", "book-pnp", "historian", 3, 0))
                    .thenReturn(List.of(new ReadingBuddyPositionedMessage(
                            "buddy", "Future relative chat", "chat", 10, 0)));
            when(memoryService.getMemorySnapshot("owner-A", "book-pnp", "historian"))
                    .thenReturn(new ReadingBuddyMemoryService.MemorySnapshot(summary, 10, 0, 1, null));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(chatProvider.generate(promptCaptor.capture(), any(LlmOptions.class)))
                    .thenReturn("Stay with the passage — later turns are still ahead.");
            when(memoryService.persistChatTurn(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(new ReadingBuddyMemoryService.ChatTurn(
                            message("u3", "user", "q"),
                            message("b3", "buddy", "Stay with the passage — later turns are still ahead.")));

            chatService.chat("owner-A", "book-pnp", "historian", "What happens later?", 3, 0);

            String livePrompt = promptCaptor.getValue();
            assertFalse(livePrompt.contains(summary));
            assertFalse(livePrompt.contains("Netherfield"));
            assertFalse(livePrompt.contains("Future relative chat"));
            assertTrue(livePrompt.contains("(No memory yet.)"));
        }
    }

    private void stubBookAndChapter(BookEntity book, int chapterIndex, String title) {
        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));
        ChapterEntity chapter = new ChapterEntity(chapterIndex, title);
        when(chapterRepository.findByBookIdAndChapterIndex(book.getId(), chapterIndex))
                .thenReturn(Optional.of(chapter));
    }

    private static BookEntity book(String id, String title, String author) {
        BookEntity book = new BookEntity(title, author, "manual");
        book.setId(id);
        return book;
    }

    private static ReadingBuddyMessageEntity message(String id, String role, String content) {
        ReadingBuddyMessageEntity entity = new ReadingBuddyMessageEntity();
        entity.setId(id);
        entity.setRole(role);
        entity.setKind("chat");
        entity.setContent(content);
        entity.setContentHash(ReadingBuddyMessageEntity.computeContentHash(role, "chat", content));
        return entity;
    }

    private static ReadingBuddyMessageEntity messageAt(
            String id, String content, int chapterIndex, int paragraphIndex) {
        ReadingBuddyMessageEntity entity = message(id, "buddy", content);
        entity.setOwnerKey("owner-A");
        entity.setBookId("book-1");
        entity.setPersonaId("humorist");
        entity.setChapterIndex(chapterIndex);
        entity.setParagraphIndex(paragraphIndex);
        entity.setCreatedAt(java.time.LocalDateTime.now());
        return entity;
    }

    /** Minimal TX manager so MemoryService history path does not require Spring TX. */
    private static final class ImmediateTxManager implements org.springframework.transaction.PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
        }
    }
}
