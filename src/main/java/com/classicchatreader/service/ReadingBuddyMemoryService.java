package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMemoryEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Durable reading-buddy messages and rolling-summary memory for an owner×book×persona thread.
 * <p>
 * PR 3b: recent-message CRUD, history with rewind visibility, clear history.
 * Rolling summary refresh is deferred (empty summary is OK).
 */
@Service
public class ReadingBuddyMemoryService {

    public static final String ROLE_USER = "user";
    public static final String ROLE_BUDDY = "buddy";
    public static final String ROLE_SYSTEM = "system";

    public static final String KIND_CHAT = "chat";
    public static final String KIND_PROACTIVE = "proactive";
    public static final String KIND_SUMMARY_MARKER = "summary_marker";

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 200;

    private final ReadingBuddyMessageRepository messageRepository;
    private final ReadingBuddyMemoryRepository memoryRepository;
    private final ReadingBuddyProperties properties;

    public ReadingBuddyMemoryService(
            ReadingBuddyMessageRepository messageRepository,
            ReadingBuddyMemoryRepository memoryRepository,
            ReadingBuddyProperties properties) {
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.properties = properties;
    }

    /**
     * Persists a message with content_hash (set on insert) and optional proactive position key.
     */
    @Transactional
    public ReadingBuddyMessageEntity saveMessage(
            String ownerKey,
            String bookId,
            String personaId,
            String role,
            String kind,
            String content,
            int chapterIndex,
            int paragraphIndex) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(personaId, "personaId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");

        ReadingBuddyMessageEntity entity = new ReadingBuddyMessageEntity();
        entity.setOwnerKey(ownerKey);
        entity.setBookId(bookId);
        entity.setPersonaId(personaId);
        entity.setRole(role);
        entity.setKind(kind);
        entity.setContent(content);
        entity.setChapterIndex(chapterIndex);
        entity.setParagraphIndex(paragraphIndex);
        if (KIND_PROACTIVE.equals(kind)) {
            entity.setProactivePositionKey(
                    ReadingBuddyMessageEntity.proactivePositionKey(chapterIndex, paragraphIndex));
        } else {
            entity.setProactivePositionKey(null);
        }
        entity.setContentHash(ReadingBuddyMessageEntity.computeContentHash(role, kind, content));
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return messageRepository.save(entity);
    }

    /**
     * Persists a user chat turn and buddy reply, updates memory last_message_id (summary unchanged).
     */
    @Transactional
    public ChatTurn persistChatTurn(
            String ownerKey,
            String bookId,
            String personaId,
            String userContent,
            String buddyContent,
            int chapterIndex,
            int paragraphIndex) {
        ReadingBuddyMessageEntity userMessage = saveMessage(
                ownerKey, bookId, personaId,
                ROLE_USER, KIND_CHAT, userContent,
                chapterIndex, paragraphIndex);
        ReadingBuddyMessageEntity buddyMessage = saveMessage(
                ownerKey, bookId, personaId,
                ROLE_BUDDY, KIND_CHAT, buddyContent,
                chapterIndex, paragraphIndex);
        touchMemoryLastMessage(ownerKey, bookId, personaId, buddyMessage.getId());
        return new ChatTurn(userMessage, buddyMessage);
    }

    /**
     * Loads recent messages for the thread (chronological), capped by config / argument.
     * Not position-filtered — callers use {@link #loadRecentMessagesForPrompt} for prompts.
     */
    @Transactional(readOnly = true)
    public List<ReadingBuddyMessageEntity> loadRecentMessages(
            String ownerKey,
            String bookId,
            String personaId,
            int limit) {
        int effectiveLimit = limit > 0 ? limit : properties.getMemory().getRecentMessages();
        List<ReadingBuddyMessageEntity> all = messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(ownerKey, bookId, personaId);
        if (all.isEmpty() || all.size() <= effectiveLimit) {
            return List.copyOf(all);
        }
        return List.copyOf(all.subList(all.size() - effectiveLimit, all.size()));
    }

    /**
     * Recent messages mapped + position-filtered for prompt injection (spoiler-safe).
     */
    @Transactional(readOnly = true)
    public List<ReadingBuddyPositionedMessage> loadRecentMessagesForPrompt(
            String ownerKey,
            String bookId,
            String personaId,
            int readerChapterIndex,
            int readerParagraphIndex) {
        int limit = Math.max(1, properties.getMemory().getRecentMessages());
        List<ReadingBuddyMessageEntity> recent = loadRecentMessages(ownerKey, bookId, personaId, limit);
        List<ReadingBuddyPositionedMessage> positioned = new ArrayList<>(recent.size());
        for (ReadingBuddyMessageEntity msg : recent) {
            positioned.add(toPositioned(msg));
        }
        return ReadingBuddyPromptBuilder.filterMessagesByPosition(
                positioned, readerChapterIndex, readerParagraphIndex);
    }

    /**
     * Returns stored rolling summary + watermarks. Empty summary is OK (PR 5 fills it).
     */
    @Transactional(readOnly = true)
    public MemorySnapshot getMemorySnapshot(String ownerKey, String bookId, String personaId) {
        return memoryRepository.findByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId)
                .map(entity -> new MemorySnapshot(
                        entity.getSummaryText() == null ? "" : entity.getSummaryText(),
                        entity.getSummaryMaxChapterIndex(),
                        entity.getSummaryMaxParagraphIndex(),
                        entity.getSummaryVersion(),
                        entity.getLastMessageId()
                ))
                .orElseGet(() -> new MemorySnapshot("", null, null, 0, null));
    }

    /**
     * Full chronology for UI history with rewind visibility flags.
     * Scoped strictly by {@code ownerKey} (IDOR-safe when controller passes identity.readerKey()).
     */
    @Transactional(readOnly = true)
    public HistoryResult getHistory(
            String ownerKey,
            String bookId,
            String personaId,
            Integer limit,
            int readerChapterIndex,
            int readerParagraphIndex,
            boolean includeHidden) {
        int effectiveLimit = normalizeHistoryLimit(limit);
        List<ReadingBuddyMessageEntity> all = messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(ownerKey, bookId, personaId);

        List<HistoryMessage> messages = new ArrayList<>();
        for (ReadingBuddyMessageEntity msg : all) {
            boolean visible = ReadingBuddyPromptBuilder.isPositionAtOrBefore(
                    msg.getChapterIndex(),
                    msg.getParagraphIndex(),
                    readerChapterIndex,
                    readerParagraphIndex);
            if (!includeHidden && !visible) {
                continue;
            }
            messages.add(toHistoryMessage(msg, visible));
        }

        if (messages.size() > effectiveLimit) {
            messages = new ArrayList<>(messages.subList(messages.size() - effectiveLimit, messages.size()));
        }

        return new HistoryResult(bookId, personaId, List.copyOf(messages));
    }

    /**
     * Deletes all messages for the thread and empties memory summary / watermarks
     * (or removes the memory row if present).
     */
    @Transactional
    public void clearHistory(String ownerKey, String bookId, String personaId) {
        messageRepository.deleteByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId);
        memoryRepository.findByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId)
                .ifPresent(memory -> {
                    memory.setSummaryText("");
                    memory.setSummaryVersion(0);
                    memory.setSummaryMaxChapterIndex(null);
                    memory.setSummaryMaxParagraphIndex(null);
                    memory.setLastMessageId(null);
                    memory.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    memoryRepository.save(memory);
                });
    }

    private void touchMemoryLastMessage(
            String ownerKey,
            String bookId,
            String personaId,
            String lastMessageId) {
        ReadingBuddyMemoryEntity memory = memoryRepository
                .findByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId)
                .orElseGet(() -> {
                    ReadingBuddyMemoryEntity created = new ReadingBuddyMemoryEntity();
                    created.setOwnerKey(ownerKey);
                    created.setBookId(bookId);
                    created.setPersonaId(personaId);
                    created.setSummaryText("");
                    created.setSummaryVersion(0);
                    return created;
                });
        memory.setLastMessageId(lastMessageId);
        // Do not bump summary_version or watermarks here — summary refresh is PR 5.
        memoryRepository.save(memory);
    }

    private static int normalizeHistoryLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(limit, MAX_HISTORY_LIMIT);
    }

    private static ReadingBuddyPositionedMessage toPositioned(ReadingBuddyMessageEntity msg) {
        return new ReadingBuddyPositionedMessage(
                msg.getRole(),
                msg.getContent(),
                msg.getKind(),
                msg.getChapterIndex(),
                msg.getParagraphIndex()
        );
    }

    private static HistoryMessage toHistoryMessage(ReadingBuddyMessageEntity msg, boolean visible) {
        String createdAt = msg.getCreatedAt() == null
                ? null
                : msg.getCreatedAt().atOffset(ZoneOffset.UTC).toString();
        return new HistoryMessage(
                msg.getId(),
                msg.getRole(),
                msg.getContent(),
                msg.getKind(),
                msg.getChapterIndex(),
                msg.getParagraphIndex(),
                createdAt,
                visible
        );
    }

    public record ChatTurn(
            ReadingBuddyMessageEntity userMessage,
            ReadingBuddyMessageEntity buddyMessage
    ) {
    }

    public record MemorySnapshot(
            String summaryText,
            Integer summaryMaxChapterIndex,
            Integer summaryMaxParagraphIndex,
            int summaryVersion,
            String lastMessageId
    ) {
    }

    public record HistoryMessage(
            String id,
            String role,
            String content,
            String kind,
            int chapterIndex,
            int paragraphIndex,
            String createdAt,
            boolean visibleAtPosition
    ) {
    }

    public record HistoryResult(
            String bookId,
            String personaId,
            List<HistoryMessage> messages
    ) {
    }
}
