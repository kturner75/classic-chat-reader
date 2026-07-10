package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMemoryEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.model.ReadingBuddyPositionedMessage;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable reading-buddy messages and rolling-summary memory for an owner×book×persona thread.
 * <p>
 * Rolling summary is refreshed <strong>inline</strong> via {@code chatLlmProvider} on a
 * {@code reading-buddy.memory.summary-every-messages} cadence (and as a hard-cap safety when
 * total exceeds max retained). Older folded messages are deleted after a successful refresh so
 * over-budget does not force an LLM call on every subsequent turn. Summary maintenance never
 * fails the caller after durable message writes.
 * <p>
 * Summary watermarks power fail-closed omit-on-rewind in {@link ReadingBuddyPromptBuilder}.
 */
@Service
public class ReadingBuddyMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ReadingBuddyMemoryService.class);

    public static final String ROLE_USER = "user";
    public static final String ROLE_BUDDY = "buddy";
    public static final String ROLE_SYSTEM = "system";

    public static final String KIND_CHAT = "chat";
    public static final String KIND_PROACTIVE = "proactive";
    public static final String KIND_SUMMARY_MARKER = "summary_marker";

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 200;
    private static final double SUMMARY_TEMPERATURE = 0.2;

    private final ReadingBuddyMessageRepository messageRepository;
    private final ReadingBuddyMemoryRepository memoryRepository;
    private final ReadingBuddyProperties properties;
    private final LlmProvider chatProvider;
    private final ReadingBuddyMetricsService metricsService;
    private final TransactionTemplate requiresNewTx;

    public ReadingBuddyMemoryService(
            ReadingBuddyMessageRepository messageRepository,
            ReadingBuddyMemoryRepository memoryRepository,
            ReadingBuddyProperties properties,
            @Qualifier("chatLlmProvider") LlmProvider chatProvider,
            ReadingBuddyMetricsService metricsService,
            PlatformTransactionManager transactionManager) {
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.properties = properties;
        this.chatProvider = chatProvider;
        this.metricsService = metricsService;
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
     * Persists a user chat turn and buddy reply, updates memory last_message_id,
     * then maybe refreshes the rolling summary (inline LLM; outside the write TX).
     */
    public ChatTurn persistChatTurn(
            String ownerKey,
            String bookId,
            String personaId,
            String userContent,
            String buddyContent,
            int chapterIndex,
            int paragraphIndex) {
        ChatTurn turn = requiresNewTx.execute(status -> {
            ReadingBuddyMessageEntity userMessage = saveMessageInCurrentTx(
                    ownerKey, bookId, personaId,
                    ROLE_USER, KIND_CHAT, userContent,
                    chapterIndex, paragraphIndex);
            ReadingBuddyMessageEntity buddyMessage = saveMessageInCurrentTx(
                    ownerKey, bookId, personaId,
                    ROLE_BUDDY, KIND_CHAT, buddyContent,
                    chapterIndex, paragraphIndex);
            touchMemoryLastMessage(ownerKey, bookId, personaId, buddyMessage.getId());
            return new ChatTurn(userMessage, buddyMessage);
        });
        // Summary is best-effort after durable write — never fail the chat path.
        safeMaybeRefreshRollingSummary(ownerKey, bookId, personaId);
        return Objects.requireNonNull(turn, "chat turn");
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
     * Rolling summary may refresh after a successful insert.
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

        ProactivePersistResult result;
        try {
            ProactivePersistResult nested = requiresNewTx.execute(status ->
                    insertProactiveInCurrentTx(
                            ownerKey, bookId, personaId, content,
                            chapterIndex, paragraphIndex, positionKey));
            result = Objects.requireNonNull(nested, "proactive persist result");
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

        if (result.inserted()) {
            // Summary is best-effort after durable write — never fail the proactive path.
            safeMaybeRefreshRollingSummary(ownerKey, bookId, personaId);
        }
        return result;
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

    private ReadingBuddyMessageEntity saveMessageInCurrentTx(
            String ownerKey,
            String bookId,
            String personaId,
            String role,
            String kind,
            String content,
            int chapterIndex,
            int paragraphIndex) {
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
     * Returns stored rolling summary + watermarks. Empty summary is OK until first refresh.
     */
    @Transactional(readOnly = true)
    public MemorySnapshot getMemorySnapshot(String ownerKey, String bookId, String personaId) {
        return memoryRepository.findByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId)
                .map(entity -> new MemorySnapshot(
                        entity.getSummaryText() == null ? "" : entity.getSummaryText(),
                        entity.getSummaryMaxChapterIndex(),
                        entity.getSummaryMaxParagraphIndex(),
                        entity.getSummaryVersion(),
                        entity.getLastMessageId(),
                        entity.getMessagesAtLastSummary()
                ))
                .orElseGet(() -> new MemorySnapshot("", null, null, 0, null, 0));
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
                    memory.setMessagesAtLastSummary(0);
                    memory.setLastMessageId(null);
                    memory.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    memoryRepository.save(memory);
                });
    }

    /**
     * Call-site wrapper: summary refresh must never fail chat/proactive after durable writes.
     */
    private void safeMaybeRefreshRollingSummary(String ownerKey, String bookId, String personaId) {
        try {
            maybeRefreshRollingSummary(ownerKey, bookId, personaId);
        } catch (Exception e) {
            try {
                metricsService.recordSummaryRefreshFailed();
            } catch (Exception ignored) {
                // metrics must not surface either
            }
            log.warn(
                    "event=buddy_memory_summarize_unexpected ownerKey={} bookId={} personaId={} errorType={} errorMessage={}",
                    truncateForLog(ownerKey, 40),
                    bookId,
                    personaId,
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
        }
    }

    /**
     * Inline rolling-summary refresh when the message-count cadence (or hard retention cap) is met.
     * LLM runs outside a write transaction. Never throws to callers.
     * <p>
     * On success: update summary + watermarks, delete folded older-than-recent messages, then
     * hard-cap at {@code maxRetainedMessages}.
     * On failure: keep prior summary; truncate to recent budget only when a prior non-empty
     * summary already covers older context (avoid deleting unsummarized history).
     */
    public void maybeRefreshRollingSummary(String ownerKey, String bookId, String personaId) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(personaId, "personaId");

        try {
            long total = messageRepository.countByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId);
            if (total <= 0) {
                return;
            }
            MemorySnapshot priorForCadence = getMemorySnapshot(ownerKey, bookId, personaId);
            if (!shouldRefreshSummary(total, priorForCadence.messagesAtLastSummary())) {
                return;
            }

            List<ReadingBuddyMessageEntity> all = messageRepository
                    .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(ownerKey, bookId, personaId);
            if (all.isEmpty()) {
                return;
            }

            int recentBudget = effectiveRecentMessages();
            // Fold older-than-recent when over recent budget; otherwise fold full chronology for cadence.
            List<ReadingBuddyMessageEntity> toFold;
            boolean deleteFoldedAfterSuccess;
            if (all.size() > recentBudget) {
                toFold = List.copyOf(all.subList(0, all.size() - recentBudget));
                deleteFoldedAfterSuccess = true;
            } else {
                toFold = List.copyOf(all);
                // Cadence under budget: compress into MEMORY but keep recent rows for conversation.
                deleteFoldedAfterSuccess = false;
            }
            if (toFold.isEmpty()) {
                return;
            }

            MemorySnapshot prior = priorForCadence;
            String priorSummary = prior.summaryText() == null ? "" : prior.summaryText().trim();
            boolean priorSummaryPresent = !priorSummary.isBlank();

            metricsService.recordSummaryRefresh();
            long started = System.currentTimeMillis();
            try {
                String prompt = buildSummaryPrompt(priorSummary, toFold);
                String generated = chatProvider.generate(
                        prompt,
                        LlmOptions.withTemperatureAndTopP(SUMMARY_TEMPERATURE, 0.9));
                String cleaned = cleanSummaryText(generated);
                if (cleaned.isBlank()) {
                    throw new IllegalStateException("blank summary from LLM");
                }

                int maxChars = Math.max(1, properties.getMemory().getSummaryMaxChars());
                if (cleaned.length() > maxChars) {
                    cleaned = cleaned.substring(0, maxChars).trim();
                }

                PositionWatermark foldWatermark = maxPosition(toFold);
                PositionWatermark watermark = mergeWatermark(
                        prior.summaryMaxChapterIndex(),
                        prior.summaryMaxParagraphIndex(),
                        priorSummary,
                        foldWatermark);

                List<String> foldedIds = deleteFoldedAfterSuccess
                        ? toFold.stream().map(ReadingBuddyMessageEntity::getId).filter(Objects::nonNull).toList()
                        : List.of();

                applySuccessfulSummary(
                        ownerKey,
                        bookId,
                        personaId,
                        cleaned,
                        watermark.chapterIndex(),
                        watermark.paragraphIndex(),
                        foldedIds);

                log.info(
                        "event=buddy_memory_summarized ownerKey={} bookId={} personaId={} folded={} deletedFolded={} versionBump=1 latencyMs={} summaryChars={}",
                        truncateForLog(ownerKey, 40),
                        bookId,
                        personaId,
                        toFold.size(),
                        foldedIds.size(),
                        System.currentTimeMillis() - started,
                        cleaned.length()
                );
            } catch (Exception e) {
                metricsService.recordSummaryRefreshFailed();
                log.warn(
                        "event=buddy_memory_summarize_failed ownerKey={} bookId={} personaId={} errorType={} errorMessage={}",
                        truncateForLog(ownerKey, 40),
                        bookId,
                        personaId,
                        e.getClass().getSimpleName(),
                        e.getMessage()
                );
                // Fail-closed for spoilers: keep prior summary/watermarks.
                // Truncate only when older turns are already represented in MEMORY — do not
                // permanently drop unsummarized history during provider outages.
                if (priorSummaryPresent) {
                    safeTruncateToBudget(ownerKey, bookId, personaId, recentBudget);
                }
            }
        } catch (Exception e) {
            // Pre-try / unexpected — never surface to chat/proactive HTTP.
            try {
                metricsService.recordSummaryRefreshFailed();
            } catch (Exception ignored) {
                // ignore
            }
            log.warn(
                    "event=buddy_memory_summarize_unexpected ownerKey={} bookId={} personaId={} errorType={} errorMessage={}",
                    truncateForLog(ownerKey, 40),
                    bookId,
                    personaId,
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
        }
    }

    /**
     * Whether total message count warrants a refresh attempt.
     * <ul>
     *   <li>Cadence: every {@code summaryEveryMessages} <em>new</em> messages since
     *       {@code messagesAtLastSummary} (count right after last successful summary).</li>
     *   <li>Hard cap safety: when total exceeds effective max retained <em>and</em> max retained
     *       is strictly greater than the recent budget (avoids continuous refresh when misconfigured
     *       {@code maxRetained <= recent}).</li>
     * </ul>
     */
    boolean shouldRefreshSummary(long totalMessages) {
        return shouldRefreshSummary(totalMessages, 0);
    }

    /**
     * @param messagesAtLastSummary thread message count stored after the last successful summary
     *                              (0 if never summarized successfully)
     */
    boolean shouldRefreshSummary(long totalMessages, int messagesAtLastSummary) {
        if (totalMessages <= 0) {
            return false;
        }
        int every = properties.getMemory().getSummaryEveryMessages();
        boolean cadence = false;
        if (every > 0) {
            if (messagesAtLastSummary <= 0) {
                // Never summarized: use absolute total multiples to avoid retrying every turn on failure.
                cadence = totalMessages >= every && (totalMessages % every == 0);
            } else {
                long since = totalMessages - (long) messagesAtLastSummary;
                // Steady-state: fire every N new messages since last success (not absolute total % every).
                cadence = since >= every && (since % every == 0);
            }
        }
        int recent = effectiveRecentMessages();
        int maxRetained = effectiveMaxRetainedMessages();
        // Only when max retained can grow beyond the recent floor; otherwise hard-cap would re-fire
        // on every message after fold-back-to-recent.
        boolean hardCapSafety = maxRetained > recent && totalMessages > maxRetained;
        return cadence || hardCapSafety;
    }

    /** recentMessages clamped to at least 1. */
    int effectiveRecentMessages() {
        return Math.max(1, properties.getMemory().getRecentMessages());
    }

    /**
     * maxRetainedMessages clamped to at least {@link #effectiveRecentMessages()} so success prune
     * is never tighter than the recent conversation budget.
     */
    int effectiveMaxRetainedMessages() {
        return Math.max(effectiveRecentMessages(), Math.max(1, properties.getMemory().getMaxRetainedMessages()));
    }

    private void applySuccessfulSummary(
            String ownerKey,
            String bookId,
            String personaId,
            String summaryText,
            int maxChapterIndex,
            int maxParagraphIndex,
            List<String> foldedMessageIdsToDelete) {
        requiresNewTx.executeWithoutResult(status -> {
            if (foldedMessageIdsToDelete != null && !foldedMessageIdsToDelete.isEmpty()) {
                messageRepository.deleteAllById(foldedMessageIdsToDelete);
            }
            // Hard ceiling after folding (no-op when already under cap).
            pruneToMaxRetained(ownerKey, bookId, personaId, effectiveMaxRetainedMessages());

            long remaining = messageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                    ownerKey, bookId, personaId);
            int baseline = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, remaining));

            ReadingBuddyMemoryEntity memory = memoryRepository
                    .findByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId)
                    .orElseGet(() -> newEmptyMemory(ownerKey, bookId, personaId));
            memory.setSummaryText(summaryText);
            memory.setSummaryMaxChapterIndex(maxChapterIndex);
            memory.setSummaryMaxParagraphIndex(maxParagraphIndex);
            memory.setSummaryVersion(memory.getSummaryVersion() + 1);
            memory.setMessagesAtLastSummary(baseline);
            memory.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            try {
                memoryRepository.save(memory);
            } catch (DataIntegrityViolationException ex) {
                ReadingBuddyMemoryEntity existing = memoryRepository
                        .findByOwnerKeyAndBookIdAndPersonaId(ownerKey, bookId, personaId)
                        .orElseThrow(() -> ex);
                existing.setSummaryText(summaryText);
                existing.setSummaryMaxChapterIndex(maxChapterIndex);
                existing.setSummaryMaxParagraphIndex(maxParagraphIndex);
                existing.setSummaryVersion(existing.getSummaryVersion() + 1);
                existing.setMessagesAtLastSummary(baseline);
                existing.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                memoryRepository.save(existing);
            }
        });
    }

    private void safeTruncateToBudget(String ownerKey, String bookId, String personaId, int budget) {
        try {
            requiresNewTx.executeWithoutResult(status ->
                    pruneToMaxRetained(ownerKey, bookId, personaId, Math.max(1, budget)));
        } catch (Exception e) {
            log.warn(
                    "event=buddy_memory_truncate_failed ownerKey={} bookId={} personaId={} errorType={} errorMessage={}",
                    truncateForLog(ownerKey, 40),
                    bookId,
                    personaId,
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
        }
    }

    private void pruneToMaxRetained(String ownerKey, String bookId, String personaId, int maxKeep) {
        List<ReadingBuddyMessageEntity> all = messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(ownerKey, bookId, personaId);
        if (all.size() <= maxKeep) {
            return;
        }
        List<String> toDelete = new ArrayList<>();
        int excess = all.size() - maxKeep;
        for (int i = 0; i < excess; i++) {
            String id = all.get(i).getId();
            if (id != null) {
                toDelete.add(id);
            }
        }
        if (!toDelete.isEmpty()) {
            messageRepository.deleteAllById(toDelete);
        }
    }

    private static String buildSummaryPrompt(String priorSummary, List<ReadingBuddyMessageEntity> toFold) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You compress a reading-buddy conversation into a durable rolling memory.
                Output ONLY the summary text (no labels, no markdown fences).
                Keep it third-person, compact, and spoiler-safe: only facts present in the prior summary
                and the messages below. Do not invent plot or future events.
                Focus on reader interests, questions, buddy asides, and ongoing threads that help later callbacks.

                PRIOR SUMMARY:
                """);
        if (priorSummary == null || priorSummary.isBlank()) {
            sb.append("(none)\n");
        } else {
            sb.append(priorSummary.trim()).append('\n');
        }
        sb.append("\nMESSAGES TO FOLD:\n");
        for (ReadingBuddyMessageEntity msg : toFold) {
            sb.append(String.format(
                    Locale.ROOT,
                    "[%s/%s ch=%d p=%d]: %s%n",
                    msg.getRole() == null ? "?" : msg.getRole(),
                    msg.getKind() == null ? "?" : msg.getKind(),
                    msg.getChapterIndex(),
                    msg.getParagraphIndex(),
                    msg.getContent() == null ? "" : msg.getContent().trim()
            ));
        }
        sb.append("\nSUMMARY:");
        return sb.toString();
    }

    static String cleanSummaryText(String generated) {
        if (generated == null) {
            return "";
        }
        String cleaned = generated.trim();
        if (cleaned.regionMatches(true, 0, "SUMMARY:", 0, "SUMMARY:".length())) {
            cleaned = cleaned.substring("SUMMARY:".length()).trim();
        }
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private static PositionWatermark maxPosition(List<ReadingBuddyMessageEntity> messages) {
        int maxChapter = Integer.MIN_VALUE;
        int maxParagraph = Integer.MIN_VALUE;
        for (ReadingBuddyMessageEntity msg : messages) {
            if (msg == null) {
                continue;
            }
            int cmp = ReadingBuddyPromptBuilder.comparePosition(
                    msg.getChapterIndex(), msg.getParagraphIndex(), maxChapter, maxParagraph);
            if (cmp > 0 || maxChapter == Integer.MIN_VALUE) {
                maxChapter = msg.getChapterIndex();
                maxParagraph = msg.getParagraphIndex();
            }
        }
        if (maxChapter == Integer.MIN_VALUE) {
            return new PositionWatermark(0, 0);
        }
        return new PositionWatermark(maxChapter, maxParagraph);
    }

    /**
     * When prior summary text is non-empty and has a full watermark, keep the max of prior and fold.
     * Otherwise use the fold watermark alone (summary content from prior is re-included in the LLM input).
     */
    private static PositionWatermark mergeWatermark(
            Integer priorChapter,
            Integer priorParagraph,
            String priorSummary,
            PositionWatermark fold) {
        boolean priorUsable = priorSummary != null
                && !priorSummary.isBlank()
                && priorChapter != null
                && priorParagraph != null;
        if (!priorUsable) {
            return fold;
        }
        if (ReadingBuddyPromptBuilder.comparePosition(
                priorChapter, priorParagraph, fold.chapterIndex(), fold.paragraphIndex()) >= 0) {
            return new PositionWatermark(priorChapter, priorParagraph);
        }
        return fold;
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
        created.setMessagesAtLastSummary(0);
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

    private static String truncateForLog(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    private record PositionWatermark(int chapterIndex, int paragraphIndex) {
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
            String lastMessageId,
            int messagesAtLastSummary
    ) {
        /** Back-compat convenience for tests that omit the cadence baseline. */
        public MemorySnapshot(
                String summaryText,
                Integer summaryMaxChapterIndex,
                Integer summaryMaxParagraphIndex,
                int summaryVersion,
                String lastMessageId) {
            this(summaryText, summaryMaxChapterIndex, summaryMaxParagraphIndex,
                    summaryVersion, lastMessageId, 0);
        }
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
