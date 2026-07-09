package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMemoryEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingBuddyMemoryServiceTest {

    @Mock
    private ReadingBuddyMessageRepository messageRepository;

    @Mock
    private ReadingBuddyMemoryRepository memoryRepository;

    private final Map<String, List<ReadingBuddyMessageEntity>> messagesByThread = new LinkedHashMap<>();
    private final Map<String, ReadingBuddyMemoryEntity> memories = new LinkedHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(1);

    private ReadingBuddyMemoryService memoryService;

    @BeforeEach
    void setUp() {
        ReadingBuddyProperties properties = new ReadingBuddyProperties();
        properties.getMemory().setRecentMessages(20);
        memoryService = new ReadingBuddyMemoryService(messageRepository, memoryRepository, properties);

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

        org.mockito.Mockito.lenient().when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
                any(), any(), any())).thenAnswer(invocation -> {
            String key = threadKey(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2));
            return List.copyOf(messagesByThread.getOrDefault(key, List.of()));
        });

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

        // Does not clear another owner's memory
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
}
