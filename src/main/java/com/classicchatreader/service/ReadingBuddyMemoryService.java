package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMemoryEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final TransactionTemplate requiresNewTx;

    public ReadingBuddyMemoryService(
            ReadingBuddyMessageRepository messageRepository,
            ReadingBuddyMemoryRepository memoryRepository,
            ReadingBuddyProperties properties,
            PlatformTransactionManager transactionManager) {
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.properties = properties;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
     * Inserts a proactive buddy comment at the given position.
     * Unique on {@code proactive_position_key}: re-checks before insert; on concurrent race
     * keeps the first row and returns it.
     * <p>
     * Insert runs in {@code REQUIRES_NEW} with {@code saveAndFlush} so the unique constraint is
     * checked before the nested TX commits. On PostgreSQL unique violation the nested TX aborts
     * independently; recovery re-queries outside that aborted TX (caller outer TX stays healthy).
     * Not annotated {@code @Transactional} so recovery reads are not bound to a doomed session.
     */
    public ProactivePersistResult persistProactiveComment(
            String ownerKey,
            String bookId,
            String personaId,
            String content,
            int chapterIndex,
            int paragraphIndex) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(personaId, "personaId");
        Objects.requireNonNull(content, "content");

        String positionKey = ReadingBuddyMessageEntity.proactivePositionKey(chapterIndex, paragraphIndex);
        Optional<ReadingBuddyMessageEntity> existing = findProactiveAtPosition(
                ownerKey, bookId, personaId, positionKey);
        if (existing.isPresent()) {
            return ProactivePersistResult.existing(existing.get());
        }

        try {
            ProactivePersistResult nested = requiresNewTx.execute(status ->
                    insertProactiveInCurrentTx(
                            ownerKey, bookId, personaId, content,
                            chapterIndex, paragraphIndex, positionKey));
            return Objects.requireNonNull(nested, "proactive persist result");
        } catch (DataIntegrityViolationException ex) {
            // Nested REQUIRES_NEW rolled back; re-query winner on a clean connection/session
            // (PostgreSQL aborts only the nested TX after unique violation).
            ReadingBuddyMessageEntity winner = requiresNewTx.execute(status ->
                    findProactiveAtPosition(ownerKey, bookId, personaId, positionKey)
                            .orElse(null));
            if (winner != null) {
                return ProactivePersistResult.existing(winner);
            }
            throw ex;
        }
    }

    /**
     * Insert + flush + memory touch inside the caller's transaction (intended for REQUIRES_NEW).
     * Re-checks existence first. Does <em>not</em> catch unique violations (must propagate so
     * the nested TX rolls back cleanly on PostgreSQL).
     */
    private ProactivePersistResult insertProactiveInCurrentTx(
            String ownerKey,
            String bookId,
            String personaId,
            String content,
            int chapterIndex,
            int paragraphIndex,
            String positionKey) {
        Optional<ReadingBuddyMessageEntity> again = findProactiveAtPosition(
                ownerKey, bookId, personaId, positionKey);
        if (again.isPresent()) {
            return ProactivePersistResult.existing(again.get());
        }

        ReadingBuddyMessageEntity entity = new ReadingBuddyMessageEntity();
        entity.setOwnerKey(ownerKey);
        entity.setBookId(bookId);
        entity.setPersonaId(personaId);
        entity.setRole(ROLE_BUDDY);
        entity.setKind(KIND_PROACTIVE);
        entity.setContent(content);
        entity.setChapterIndex(chapterIndex);
        entity.setParagraphIndex(paragraphIndex);
        entity.setProactivePositionKey(positionKey);
        entity.setContentHash(ReadingBuddyMessageEntity.computeContentHash(ROLE_BUDDY, KIND_PROACTIVE, content));
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        // saveAndFlush forces unique-index check before nested TX commit (GenerationType.UUID
        // would otherwise defer INSERT until flush/commit, missing our catch path).
        ReadingBuddyMessageEntity saved = messageRepository.saveAndFlush(entity);
        touchMemoryLastMessage(ownerKey, bookId, personaId, saved.getId());
        return ProactivePersistResult.inserted(saved);
    }

    private Optional<ReadingBuddyMessageEntity> findProactiveAtPosition(
            String ownerKey,
            String bookId,
            String personaId,
            String positionKey) {
        return messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                ownerKey, bookId, personaId, positionKey);
    }

    /**
     * Whether a proactive comment already exists at this position for the thread.
     */
    @Transactional(readOnly = true)
    public boolean hasProactiveAtPosition(
            String ownerKey,
            String bookId,
            String personaId,
            int chapterIndex,
            int paragraphIndex) {
        String positionKey = ReadingBuddyMessageEntity.proactivePositionKey(chapterIndex, paragraphIndex);
        return findProactiveAtPosition(ownerKey, bookId, personaId, positionKey).isPresent();
    }

    /**
     * Result of a proactive insert attempt: whether a new row was written.
     */
    public record ProactivePersistResult(ReadingBuddyMessageEntity message, boolean inserted) {
        public static ProactivePersistResult inserted(ReadingBuddyMessageEntity message) {
            return new ProactivePersistResult(Objects.requireNonNull(message), true);
        }

        public static ProactivePersistResult existing(ReadingBuddyMessageEntity message) {
            return new ProactivePersistResult(Objects.requireNonNull(message), false);
        }
    }

    /**
     * Loads recent messages for the thread (chronological), capped by config / argument.
     * Not position-filtered — callers use {@link #loadRecentMessagesForPrompt} for prompts.
     * Uses a DB-side {@code LIMIT} (newest first, then reversed to chronological).
     */
    @Transactional(readOnly = true)
    public List<ReadingBuddyMessageEntity> loadRecentMessages(
            String ownerKey,
            String bookId,
            String personaId,
            int limit) {
        int effectiveLimit = limit > 0 ? limit : properties.getMemory().getRecentMessages();
        effectiveLimit = Math.max(1, effectiveLimit);
        List<ReadingBuddyMessageEntity> newestFirst = messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtDesc(
                        ownerKey, bookId, personaId, PageRequest.of(0, effectiveLimit));
        return chronologicalCopy(newestFirst);
    }

    /**
     * Recent messages for prompt injection: <strong>position-filter first, then take last N</strong>
     * (spoiler-safe and rewind-safe). Uses a position-bounded DB query with {@code LIMIT}.
     */
    @Transactional(readOnly = true)
    public List<ReadingBuddyPositionedMessage> loadRecentMessagesForPrompt(
            String ownerKey,
            String bookId,
            String personaId,
            int readerChapterIndex,
            int readerParagraphIndex) {
        int limit = Math.max(1, properties.getMemory().getRecentMessages());
        // Filter by position at the DB (or equivalent), then take newest N of the safe set.
        List<ReadingBuddyMessageEntity> newestSafeFirst = messageRepository
                .findVisibleAtOrBeforeOrderByCreatedAtDesc(
                        ownerKey,
                        bookId,
                        personaId,
                        readerChapterIndex,
                        readerParagraphIndex,
                        PageRequest.of(0, limit));
        List<ReadingBuddyMessageEntity> chronological = chronologicalCopy(newestSafeFirst);
        List<ReadingBuddyPositionedMessage> positioned = new ArrayList<>(chronological.size());
        for (ReadingBuddyMessageEntity msg : chronological) {
            positioned.add(toPositioned(msg));
        }
        return List.copyOf(positioned);
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
     * Applies visibility first when {@code includeHidden} is false, then limits to last N.
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
        List<ReadingBuddyMessageEntity> pageNewestFirst;
        if (includeHidden) {
            // Include future-relative rows (UI collapses them via visibleAtPosition).
            pageNewestFirst = messageRepository.findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtDesc(
                    ownerKey, bookId, personaId, PageRequest.of(0, effectiveLimit));
        } else {
            // Visible-only: position-filter first, then last N.
            pageNewestFirst = messageRepository.findVisibleAtOrBeforeOrderByCreatedAtDesc(
                    ownerKey,
                    bookId,
                    personaId,
                    readerChapterIndex,
                    readerParagraphIndex,
                    PageRequest.of(0, effectiveLimit));
        }

        List<ReadingBuddyMessageEntity> chronological = chronologicalCopy(pageNewestFirst);
        List<HistoryMessage> messages = new ArrayList<>(chronological.size());
        for (ReadingBuddyMessageEntity msg : chronological) {
            boolean visible = ReadingBuddyPromptBuilder.isPositionAtOrBefore(
                    msg.getChapterIndex(),
                    msg.getParagraphIndex(),
                    readerChapterIndex,
                    readerParagraphIndex);
            messages.add(toHistoryMessage(msg, visible));
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
                .orElseGet(() -> newEmptyMemory(ownerKey, bookId, personaId));
        memory.setLastMessageId(lastMessageId);
        // Do not bump summary_version or watermarks here — summary refresh is PR 5.
        try {
            memoryRepository.save(memory);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent first-insert race on uk_rbmem_owner_book_persona — reload winner.
            ReadingBuddyMemoryEntity existing = memoryRepository
                    .findByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId)
                    .orElseThrow(() -> ex);
            existing.setLastMessageId(lastMessageId);
            memoryRepository.save(existing);
        }
    }

    private static ReadingBuddyMemoryEntity newEmptyMemory(
            String ownerKey, String bookId, String personaId) {
        ReadingBuddyMemoryEntity created = new ReadingBuddyMemoryEntity();
        created.setOwnerKey(ownerKey);
        created.setBookId(bookId);
        created.setPersonaId(personaId);
        created.setSummaryText("");
        created.setSummaryVersion(0);
        return created;
    }

    /**
     * Reverses a newest-first list into chronological ASC order (defensive copy).
     */
    private static List<ReadingBuddyMessageEntity> chronologicalCopy(
            List<ReadingBuddyMessageEntity> newestFirst) {
        if (newestFirst == null || newestFirst.isEmpty()) {
            return List.of();
        }
        List<ReadingBuddyMessageEntity> chrono = new ArrayList<>(newestFirst);
        Collections.reverse(chrono);
        return List.copyOf(chrono);
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
