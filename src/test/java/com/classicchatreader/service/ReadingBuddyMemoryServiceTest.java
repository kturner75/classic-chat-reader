package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMemoryEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingBuddyMemoryServiceTest {

    @Mock
    private ReadingBuddyMessageRepository messageRepository;

    @Mock
    private ReadingBuddyMemoryRepository memoryRepository;

    @Mock
    private LlmProvider chatProvider;

    @Mock
    private ReadingBuddyMetricsService metricsService;

    private final Map<String, List<ReadingBuddyMessageEntity>> messagesByThread = new LinkedHashMap<>();
    private final Map<String, ReadingBuddyMemoryEntity> memories = new LinkedHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(1);

    private ReadingBuddyProperties properties;
    private ReadingBuddyMemoryService memoryService;

    @BeforeEach
    void setUp() {
        properties = new ReadingBuddyProperties();
        properties.getMemory().setRecentMessages(20);
        properties.getMemory().setSummaryEveryMessages(8);
        properties.getMemory().setMaxRetainedMessages(100);
        memoryService = new ReadingBuddyMemoryService(
                messageRepository,
                memoryRepository,
                properties,
                chatProvider,
                metricsService,
                new ImmediateTransactionManager());

        org.mockito.Mockito.lenient().when(messageRepository.save(any(ReadingBuddyMessageEntity.class)))
                .thenAnswer(invocation -> {
                    ReadingBuddyMessageEntity entity = invocation.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId("msg-" + idSeq.getAndIncrement());
                    }
                    String key = threadKey(entity.getOwnerKey(), entity.getBookId(), entity.getPersonaId());
                    messagesByThread.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
                    return entity;
                });
        org.mockito.Mockito.lenient().when(messageRepository.saveAndFlush(any(ReadingBuddyMessageEntity.class)))
                .thenAnswer(invocation -> {
                    ReadingBuddyMessageEntity entity = invocation.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId("msg-" + idSeq.getAndIncrement());
                    }
                    String key = threadKey(entity.getOwnerKey(), entity.getBookId(), entity.getPersonaId());
                    messagesByThread.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
                    return entity;
                });

        org.mockito.Mockito.lenient().when(messageRepository
                        .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtDesc(
                                any(), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    String key = threadKey(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2));
                    Pageable pageable = invocation.getArgument(3);
                    return newestFirstPage(messagesByThread.getOrDefault(key, List.of()), pageable.getPageSize());
                });

        org.mockito.Mockito.lenient().when(messageRepository
                        .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(any(), any(), any()))
                .thenAnswer(invocation -> {
                    String key = threadKey(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2));
                    return messagesByThread.getOrDefault(key, List.of()).stream()
                            .sorted(Comparator.comparing(ReadingBuddyMessageEntity::getCreatedAt))
                            .collect(Collectors.toList());
                });

        org.mockito.Mockito.lenient().when(messageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                        any(), any(), any()))
                .thenAnswer(invocation -> {
                    String key = threadKey(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2));
                    return (long) messagesByThread.getOrDefault(key, List.of()).size();
                });

        org.mockito.Mockito.lenient().when(messageRepository.findVisibleAtOrBeforeOrderByCreatedAtDesc(
                        any(), any(), any(), anyInt(), anyInt(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    String key = threadKey(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2));
                    int readerChapter = invocation.getArgument(3);
                    int readerParagraph = invocation.getArgument(4);
                    Pageable pageable = invocation.getArgument(5);
                    List<ReadingBuddyMessageEntity> visible = messagesByThread
                            .getOrDefault(key, List.of())
                            .stream()
                            .filter(m -> ReadingBuddyPromptBuilder.isPositionAtOrBefore(
                                    m.getChapterIndex(),
                                    m.getParagraphIndex(),
                                    readerChapter,
                                    readerParagraph))
                            .collect(Collectors.toCollection(ArrayList::new));
                    return newestFirstPage(visible, pageable.getPageSize());
                });

        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Iterable<String> ids = (Iterable<String>) invocation.getArgument(0);
            for (String id : ids) {
                for (List<ReadingBuddyMessageEntity> list : messagesByThread.values()) {
                    list.removeIf(m -> id.equals(m.getId()));
                }
            }
            return null;
        }).when(messageRepository).deleteAllById(any());

        org.mockito.Mockito.lenient().when(memoryRepository.findByOwnerKeyAndBookIdAndPersonaId(any(), any(), any()))
                .thenAnswer(invocation -> {
                    String key = threadKey(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2));
                    return Optional.ofNullable(memories.get(key));
                });

        org.mockito.Mockito.lenient().when(memoryRepository.save(any(ReadingBuddyMemoryEntity.class)))
                .thenAnswer(invocation -> {
                    ReadingBuddyMemoryEntity entity = invocation.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId("mem-" + idSeq.getAndIncrement());
                    }
                    memories.put(threadKey(entity.getOwnerKey(), entity.getBookId(), entity.getPersonaId()), entity);
                    return entity;
                });
    }

    @Test
    void saveMessage_setsContentHashAndNoProactiveKeyForChat() {
        ReadingBuddyMessageEntity saved = memoryService.saveMessage(
                "owner-A", "book-1", "humorist",
                ReadingBuddyMemoryService.ROLE_USER,
                ReadingBuddyMemoryService.KIND_CHAT,
                "hello",
                1, 2);

        assertNotNull(saved.getId());
        assertEquals(
                ReadingBuddyMessageEntity.computeContentHash("user", "chat", "hello"),
                saved.getContentHash());
        assertNull(saved.getProactivePositionKey());
    }

    @Test
    void saveMessage_setsProactivePositionKeyForProactiveKind() {
        ReadingBuddyMessageEntity saved = memoryService.saveMessage(
                "owner-A", "book-1", "humorist",
                ReadingBuddyMemoryService.ROLE_BUDDY,
                ReadingBuddyMemoryService.KIND_PROACTIVE,
                "nice line",
                3, 12);

        assertEquals("3:12", saved.getProactivePositionKey());
    }

    @Test
    void persistChatTurn_savesUserAndBuddyAndTouchesMemory() {
        ReadingBuddyMemoryService.ChatTurn turn = memoryService.persistChatTurn(
                "owner-A", "book-1", "humorist",
                "user text", "buddy text",
                2, 5);

        assertEquals("user", turn.userMessage().getRole());
        assertEquals("buddy", turn.buddyMessage().getRole());
        assertEquals("chat", turn.userMessage().getKind());
        assertEquals(2, messagesByThread.get(threadKey("owner-A", "book-1", "humorist")).size());

        ReadingBuddyMemoryEntity memory = memories.get(threadKey("owner-A", "book-1", "humorist"));
        assertNotNull(memory);
        assertEquals(turn.buddyMessage().getId(), memory.getLastMessageId());
        assertEquals("", memory.getSummaryText());
        assertNull(memory.getSummaryMaxChapterIndex());
    }

    @Test
    void loadRecentMessagesForPrompt_filtersFuturePositions() {
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "early", 1, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "mid", 2, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "proactive", "future", 5, 0);

        List<ReadingBuddyPositionedMessage> forPrompt = memoryService.loadRecentMessagesForPrompt(
                "owner-A", "book-1", "humorist", 2, 0);

        assertEquals(2, forPrompt.size());
        assertEquals("early", forPrompt.get(0).content());
        assertEquals("mid", forPrompt.get(1).content());
    }

    @Test
    void loadRecentMessagesForPrompt_filterThenLimit_survivesRewind() {
        // Seed: chapters 1, 2, 5, 10. Small recent limit would only cover 5–10 if sliced first.
        properties.getMemory().setRecentMessages(2);
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "ch1", 1, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "ch2", 2, 0);
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "ch5", 5, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "ch10", 10, 0);

        List<ReadingBuddyPositionedMessage> forPrompt = memoryService.loadRecentMessagesForPrompt(
                "owner-A", "book-1", "humorist", 2, 0);

        assertEquals(2, forPrompt.size());
        assertEquals("ch1", forPrompt.get(0).content());
        assertEquals("ch2", forPrompt.get(1).content());
        assertTrue(forPrompt.stream().noneMatch(m -> m.content().equals("ch5")));
        assertTrue(forPrompt.stream().noneMatch(m -> m.content().equals("ch10")));
    }

    @Test
    void getHistory_marksVisibleAtPositionAndIncludeHidden() {
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "visible", 1, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "hidden", 9, 0);

        ReadingBuddyMemoryService.HistoryResult withHidden = memoryService.getHistory(
                "owner-A", "book-1", "humorist", 50, 1, 0, true);
        assertEquals(2, withHidden.messages().size());
        assertTrue(withHidden.messages().get(0).visibleAtPosition());
        assertFalse(withHidden.messages().get(1).visibleAtPosition());

        ReadingBuddyMemoryService.HistoryResult visibleOnly = memoryService.getHistory(
                "owner-A", "book-1", "humorist", 50, 1, 0, false);
        assertEquals(1, visibleOnly.messages().size());
        assertEquals("visible", visibleOnly.messages().get(0).content());
    }

    @Test
    void getHistory_isScopedByOwnerKey_idor() {
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "A secret", 0, 0);
        seedMessage("owner-B", "book-1", "humorist", "user", "chat", "B secret", 0, 0);

        ReadingBuddyMemoryService.HistoryResult forA = memoryService.getHistory(
                "owner-A", "book-1", "humorist", 50, 0, 0, true);

        assertEquals(1, forA.messages().size());
        assertEquals("A secret", forA.messages().get(0).content());
    }

    @Test
    void clearHistory_deletesMessagesAndEmptiesMemory() {
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "hello", 0, 0);
        ReadingBuddyMemoryEntity memory = new ReadingBuddyMemoryEntity();
        memory.setId("mem-1");
        memory.setOwnerKey("owner-A");
        memory.setBookId("book-1");
        memory.setPersonaId("humorist");
        memory.setSummaryText("prior summary");
        memory.setSummaryVersion(3);
        memory.setSummaryMaxChapterIndex(4);
        memory.setSummaryMaxParagraphIndex(2);
        memory.setLastMessageId("msg-x");
        memories.put(threadKey("owner-A", "book-1", "humorist"), memory);

        memoryService.clearHistory("owner-A", "book-1", "humorist");

        verify(messageRepository).deleteByOwnerKeyAndBookIdAndPersonaId("owner-A", "book-1", "humorist");
        ArgumentCaptor<ReadingBuddyMemoryEntity> captor = ArgumentCaptor.forClass(ReadingBuddyMemoryEntity.class);
        verify(memoryRepository).save(captor.capture());
        ReadingBuddyMemoryEntity cleared = captor.getValue();
        assertEquals("", cleared.getSummaryText());
        assertEquals(0, cleared.getSummaryVersion());
        assertNull(cleared.getSummaryMaxChapterIndex());
        assertNull(cleared.getSummaryMaxParagraphIndex());
        assertNull(cleared.getLastMessageId());

        verify(messageRepository, never())
                .deleteByOwnerKeyAndBookIdAndPersonaId(eq("owner-B"), any(), any());
    }

    @Test
    void getMemorySnapshot_emptyWhenMissing() {
        ReadingBuddyMemoryService.MemorySnapshot snapshot =
                memoryService.getMemorySnapshot("owner-A", "book-1", "humorist");
        assertEquals("", snapshot.summaryText());
        assertNull(snapshot.summaryMaxChapterIndex());
        assertEquals(0, snapshot.summaryVersion());
    }

    @Test
    void shouldRefreshSummary_cadenceUsesMessagesSinceLastSummary_notAbsoluteTotal() {
        properties.getMemory().setSummaryEveryMessages(8);
        properties.getMemory().setRecentMessages(20);
        properties.getMemory().setMaxRetainedMessages(100);

        // Never summarized (baseline 0): absolute multiples.
        assertFalse(memoryService.shouldRefreshSummary(0, 0));
        assertFalse(memoryService.shouldRefreshSummary(7, 0));
        assertTrue(memoryService.shouldRefreshSummary(8, 0));
        assertTrue(memoryService.shouldRefreshSummary(16, 0));
        assertFalse(memoryService.shouldRefreshSummary(9, 0));
        assertFalse(memoryService.shouldRefreshSummary(21, 0));

        // After success folded back to 20 remaining → baseline 20.
        // Next refresh only after 8 new messages (total 28), not at 24 (old absolute % bug).
        assertFalse(memoryService.shouldRefreshSummary(21, 20));
        assertFalse(memoryService.shouldRefreshSummary(24, 20));
        assertFalse(memoryService.shouldRefreshSummary(27, 20));
        assertTrue(memoryService.shouldRefreshSummary(28, 20));
        assertFalse(memoryService.shouldRefreshSummary(29, 20));
        assertTrue(memoryService.shouldRefreshSummary(36, 20));

        // Hard cap safety when maxRetained > recent.
        assertTrue(memoryService.shouldRefreshSummary(101, 20));
        // When maxRetained clamped equal to recent, hard-cap disabled (avoids continuous refresh).
        properties.getMemory().setMaxRetainedMessages(5);
        properties.getMemory().setRecentMessages(20);
        assertFalse(memoryService.shouldRefreshSummary(21, 20));
    }

    @Test
    void effectiveMaxRetained_clampsToAtLeastRecentMessages() {
        properties.getMemory().setRecentMessages(20);
        properties.getMemory().setMaxRetainedMessages(10);
        assertEquals(20, memoryService.effectiveMaxRetainedMessages());
        assertEquals(20, memoryService.effectiveRecentMessages());

        properties.getMemory().setMaxRetainedMessages(50);
        assertEquals(50, memoryService.effectiveMaxRetainedMessages());
    }

    @Test
    void maybeRefreshRollingSummary_setsWatermarksAndIncrementsVersion() {
        properties.getMemory().setSummaryEveryMessages(4);
        properties.getMemory().setRecentMessages(20);
        // Positions up to chapter 10 so watermark can be ch10 for omit tests.
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "q1", 1, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "a1", 1, 0);
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "q2", 10, 2);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "a2", 10, 2);

        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("Reader asked about early scenes; later returned at chapter 10.");

        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");

        verify(metricsService).recordSummaryRefresh();
        verify(metricsService, never()).recordSummaryRefreshFailed();
        verify(chatProvider).generate(anyString(), any(LlmOptions.class));

        ReadingBuddyMemoryEntity memory = memories.get(threadKey("owner-A", "book-1", "humorist"));
        assertNotNull(memory);
        assertTrue(memory.getSummaryText().contains("chapter 10")
                || memory.getSummaryText().contains("Reader asked"));
        assertEquals(10, memory.getSummaryMaxChapterIndex());
        assertEquals(2, memory.getSummaryMaxParagraphIndex());
        assertEquals(1, memory.getSummaryVersion());
        // Under recent budget: cadence fold keeps messages for conversation context.
        assertEquals(4, messagesByThread.get(threadKey("owner-A", "book-1", "humorist")).size());
    }

    @Test
    void maybeRefreshRollingSummary_onFailure_withPriorSummary_truncatesToRecent() {
        properties.getMemory().setSummaryEveryMessages(4);
        properties.getMemory().setRecentMessages(2);
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "old-1", 1, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "old-2", 1, 1);
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "new-1", 2, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "new-2", 2, 1);

        ReadingBuddyMemoryEntity prior = new ReadingBuddyMemoryEntity();
        prior.setId("mem-prior");
        prior.setOwnerKey("owner-A");
        prior.setBookId("book-1");
        prior.setPersonaId("humorist");
        prior.setSummaryText("prior summary text");
        prior.setSummaryVersion(2);
        prior.setSummaryMaxChapterIndex(1);
        prior.setSummaryMaxParagraphIndex(1);
        memories.put(threadKey("owner-A", "book-1", "humorist"), prior);

        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenThrow(new RuntimeException("provider down"));

        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");

        verify(metricsService).recordSummaryRefresh();
        verify(metricsService).recordSummaryRefreshFailed();

        ReadingBuddyMemoryEntity memory = memories.get(threadKey("owner-A", "book-1", "humorist"));
        assertEquals("prior summary text", memory.getSummaryText());
        assertEquals(2, memory.getSummaryVersion());
        assertEquals(1, memory.getSummaryMaxChapterIndex());

        List<ReadingBuddyMessageEntity> remaining =
                messagesByThread.get(threadKey("owner-A", "book-1", "humorist"));
        assertEquals(2, remaining.size());
        assertTrue(remaining.stream().anyMatch(m -> "new-1".equals(m.getContent())));
        assertTrue(remaining.stream().anyMatch(m -> "new-2".equals(m.getContent())));
        assertTrue(remaining.stream().noneMatch(m -> "old-1".equals(m.getContent())));
    }

    @Test
    void maybeRefreshRollingSummary_onFailure_emptyPrior_doesNotDeleteMessages() {
        properties.getMemory().setSummaryEveryMessages(4);
        properties.getMemory().setRecentMessages(2);
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "old-1", 1, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "old-2", 1, 1);
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "new-1", 2, 0);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "new-2", 2, 1);

        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenThrow(new RuntimeException("provider down"));

        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");

        verify(metricsService).recordSummaryRefreshFailed();
        List<ReadingBuddyMessageEntity> remaining =
                messagesByThread.get(threadKey("owner-A", "book-1", "humorist"));
        assertEquals(4, remaining.size(), "empty prior must not drop unsummarized history");
        ReadingBuddyMemoryService.MemorySnapshot snap =
                memoryService.getMemorySnapshot("owner-A", "book-1", "humorist");
        assertEquals("", snap.summaryText());
    }

    @Test
    void maybeRefreshRollingSummary_skipsWhenBelowThresholds() {
        properties.getMemory().setSummaryEveryMessages(8);
        properties.getMemory().setRecentMessages(20);
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "only", 0, 0);

        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");

        verify(chatProvider, never()).generate(anyString(), any(LlmOptions.class));
        verify(metricsService, never()).recordSummaryRefresh();
    }

    @Test
    void maybeRefreshRollingSummary_afterSuccess_doesNotRefreshAgainUntilNextCadence() {
        properties.getMemory().setSummaryEveryMessages(8);
        properties.getMemory().setRecentMessages(5);
        properties.getMemory().setMaxRetainedMessages(100);

        // 8 messages on cadence → refresh; over recent (5) so folded older 3 are deleted.
        for (int i = 0; i < 8; i++) {
            seedMessage("owner-A", "book-1", "humorist", "user", "chat", "m" + i, i, 0);
        }
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("Compact summary of early turns.");

        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");
        verify(chatProvider, org.mockito.Mockito.times(1)).generate(anyString(), any(LlmOptions.class));

        List<ReadingBuddyMessageEntity> afterFirst =
                messagesByThread.get(threadKey("owner-A", "book-1", "humorist"));
        assertEquals(5, afterFirst.size(), "folded older-than-recent deleted after success");
        assertEquals(5, memories.get(threadKey("owner-A", "book-1", "humorist")).getMessagesAtLastSummary());

        // +3 messages → total 8; old absolute-% would fire at total 8, but since-last is only 3.
        for (int i = 0; i < 3; i++) {
            seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "early" + i, 20 + i, 0);
        }
        assertEquals(8, messagesByThread.get(threadKey("owner-A", "book-1", "humorist")).size());
        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");
        verify(chatProvider, org.mockito.Mockito.times(1)).generate(anyString(), any(LlmOptions.class));

        // +5 more → total 13; since last summary = 8 → cadence fires.
        for (int i = 0; i < 5; i++) {
            seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "late" + i, 30 + i, 0);
        }
        assertEquals(13, messagesByThread.get(threadKey("owner-A", "book-1", "humorist")).size());
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("Second compact summary.");
        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");
        verify(chatProvider, org.mockito.Mockito.times(2)).generate(anyString(), any(LlmOptions.class));
        // Folded down to recent budget again; baseline updated to remaining count.
        assertEquals(5, messagesByThread.get(threadKey("owner-A", "book-1", "humorist")).size());
        assertEquals(5, memories.get(threadKey("owner-A", "book-1", "humorist")).getMessagesAtLastSummary());
    }

    @Test
    void maybeRefreshRollingSummary_success_prunesToMaxRetained() {
        properties.getMemory().setSummaryEveryMessages(5);
        properties.getMemory().setRecentMessages(3);
        properties.getMemory().setMaxRetainedMessages(3);

        for (int i = 0; i < 5; i++) {
            seedMessage("owner-A", "book-1", "humorist", "user", "chat", "row" + i, i, 0);
        }
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("Five-turn summary.");

        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");

        List<ReadingBuddyMessageEntity> remaining =
                messagesByThread.get(threadKey("owner-A", "book-1", "humorist"));
        assertEquals(3, remaining.size());
        assertEquals(1, memories.get(threadKey("owner-A", "book-1", "humorist")).getSummaryVersion());
        // Newest three kept
        assertTrue(remaining.stream().anyMatch(m -> "row4".equals(m.getContent())));
        assertTrue(remaining.stream().noneMatch(m -> "row0".equals(m.getContent())));
    }

    @Test
    void maybeRefreshRollingSummary_mergesWatermarkWithPriorSummary() {
        properties.getMemory().setSummaryEveryMessages(2);
        properties.getMemory().setRecentMessages(20);

        ReadingBuddyMemoryEntity prior = new ReadingBuddyMemoryEntity();
        prior.setId("mem-prior");
        prior.setOwnerKey("owner-A");
        prior.setBookId("book-1");
        prior.setPersonaId("humorist");
        prior.setSummaryText("Earlier discussion at chapter 12.");
        prior.setSummaryVersion(1);
        prior.setSummaryMaxChapterIndex(12);
        prior.setSummaryMaxParagraphIndex(0);
        memories.put(threadKey("owner-A", "book-1", "humorist"), prior);

        // New fold only reaches chapter 3 — prior watermark (12) must win.
        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "rewind chat", 3, 1);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "ok", 3, 1);

        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("Updated summary still covering earlier high-water material.");

        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");

        ReadingBuddyMemoryEntity memory = memories.get(threadKey("owner-A", "book-1", "humorist"));
        assertEquals(12, memory.getSummaryMaxChapterIndex());
        assertEquals(0, memory.getSummaryMaxParagraphIndex());
        assertEquals(2, memory.getSummaryVersion());
    }

    @Test
    void maybeRefreshRollingSummary_secondFoldRaisesWatermark() {
        properties.getMemory().setSummaryEveryMessages(2);
        properties.getMemory().setRecentMessages(20);

        ReadingBuddyMemoryEntity prior = new ReadingBuddyMemoryEntity();
        prior.setId("mem-prior");
        prior.setOwnerKey("owner-A");
        prior.setBookId("book-1");
        prior.setPersonaId("humorist");
        prior.setSummaryText("Early notes.");
        prior.setSummaryVersion(1);
        prior.setSummaryMaxChapterIndex(2);
        prior.setSummaryMaxParagraphIndex(0);
        memories.put(threadKey("owner-A", "book-1", "humorist"), prior);

        seedMessage("owner-A", "book-1", "humorist", "user", "chat", "later", 10, 5);
        seedMessage("owner-A", "book-1", "humorist", "buddy", "chat", "reply", 10, 5);

        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("Now includes chapter 10 material.");

        memoryService.maybeRefreshRollingSummary("owner-A", "book-1", "humorist");

        ReadingBuddyMemoryEntity memory = memories.get(threadKey("owner-A", "book-1", "humorist"));
        assertEquals(10, memory.getSummaryMaxChapterIndex());
        assertEquals(5, memory.getSummaryMaxParagraphIndex());
    }

    @Test
    void persistChatTurn_summaryFailureDoesNotPropagate() {
        properties.getMemory().setSummaryEveryMessages(2);
        properties.getMemory().setRecentMessages(20);
        // After this turn we will have 2 messages → cadence triggers summary.
        when(chatProvider.generate(anyString(), any(LlmOptions.class)))
                .thenThrow(new RuntimeException("summary boom"));

        ReadingBuddyMemoryService.ChatTurn turn = memoryService.persistChatTurn(
                "owner-A", "book-1", "humorist",
                "user text", "buddy text",
                1, 0);

        assertNotNull(turn.userMessage().getId());
        assertNotNull(turn.buddyMessage().getId());
        verify(metricsService).recordSummaryRefreshFailed();
    }

    @Test
    void cleanSummaryText_stripsLabelAndQuotes() {
        assertEquals("Hello world", ReadingBuddyMemoryService.cleanSummaryText("SUMMARY: Hello world"));
        assertEquals("Hi", ReadingBuddyMemoryService.cleanSummaryText("\"Hi\""));
        assertEquals("", ReadingBuddyMemoryService.cleanSummaryText("  "));
    }

    private static List<ReadingBuddyMessageEntity> newestFirstPage(
            List<ReadingBuddyMessageEntity> source, int limit) {
        return source.stream()
                .sorted(Comparator.comparing(ReadingBuddyMessageEntity::getCreatedAt).reversed())
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    private void seedMessage(
            String ownerKey,
            String bookId,
            String personaId,
            String role,
            String kind,
            String content,
            int chapter,
            int paragraph) {
        ReadingBuddyMessageEntity entity = new ReadingBuddyMessageEntity();
        entity.setId("msg-" + idSeq.getAndIncrement());
        entity.setOwnerKey(ownerKey);
        entity.setBookId(bookId);
        entity.setPersonaId(personaId);
        entity.setRole(role);
        entity.setKind(kind);
        entity.setContent(content);
        entity.setChapterIndex(chapter);
        entity.setParagraphIndex(paragraph);
        entity.setContentHash(ReadingBuddyMessageEntity.computeContentHash(role, kind, content));
        entity.setCreatedAt(LocalDateTime.of(2026, 7, 8, 12, 0).plusSeconds(idSeq.get()));
        messagesByThread.computeIfAbsent(threadKey(ownerKey, bookId, personaId), k -> new ArrayList<>())
                .add(entity);
    }

    private static String threadKey(String ownerKey, String bookId, String personaId) {
        return ownerKey + "|" + bookId + "|" + personaId;
    }

    /** Runs callbacks immediately (unit-test stand-in for REQUIRES_NEW). */
    static final class ImmediateTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
        }
    }
}
