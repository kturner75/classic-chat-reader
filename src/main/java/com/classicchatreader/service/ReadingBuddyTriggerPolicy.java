package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Server-side hard filters for proactive reading-buddy comments. <strong>No LLM.</strong>
 * Returns {@link TriggerDecision.Eligible} or {@link TriggerDecision.Silence}.
 */
@Component
public class ReadingBuddyTriggerPolicy {

    public enum SilenceReason {
        SUPPRESSED,
        ALREADY_COMMENTED,
        PARAGRAPH_GAP,
        COOLDOWN,
        RATE_CAP,
        POST_CHAT_GAP,
        DECIDED_NONE
    }

    private final ReadingBuddyMessageRepository messageRepository;
    private final ReadingBuddyProperties properties;

    public ReadingBuddyTriggerPolicy(
            ReadingBuddyMessageRepository messageRepository,
            ReadingBuddyProperties properties) {
        this.messageRepository = messageRepository;
        this.properties = properties;
    }

    /**
     * Evaluates hard filters only. Prefer calling with a clock for tests.
     */
    public TriggerDecision evaluate(TriggerContext context) {
        return evaluate(context, LocalDateTime.now(ZoneOffset.UTC));
    }

    public TriggerDecision evaluate(TriggerContext context, LocalDateTime nowUtc) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        LocalDateTime now = nowUtc != null ? nowUtc : LocalDateTime.now(ZoneOffset.UTC);

        // 1. Prefs disabled or suppress_until active
        if (!context.enabled()) {
            return silence(SilenceReason.SUPPRESSED, defaultCooldownMs(context.frequency()));
        }
        if (context.suppressUntilEpochMs() != null && context.suppressUntilEpochMs() > 0) {
            long nowMs = toEpochMs(now);
            long remaining = context.suppressUntilEpochMs() - nowMs;
            if (remaining > 0) {
                return silence(SilenceReason.SUPPRESSED, remaining);
            }
        }

        String ownerKey = context.ownerKey();
        String bookId = context.bookId();
        String personaId = context.personaId();
        int chapter = context.chapterIndex();
        int paragraph = context.paragraphIndex();
        String frequency = context.frequency() == null ? "rare" : context.frequency();

        // 2. Same position already has a proactive comment
        String positionKey = ReadingBuddyMessageEntity.proactivePositionKey(chapter, paragraph);
        if (messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                        ownerKey, bookId, personaId, positionKey)
                .isPresent()) {
            return silence(SilenceReason.ALREADY_COMMENTED, defaultCooldownMs(frequency));
        }

        ReadingBuddyMessageEntity lastProactive = latestProactive(ownerKey, bookId, personaId);
        long minCooldownMs = Math.max(0L, properties.minCooldownMsFor(frequency));
        int minParagraphGap = Math.max(0, properties.minParagraphGapFor(frequency));

        // 3. Min paragraphs since last proactive
        if (lastProactive != null) {
            int steps = paragraphStepsForward(
                    lastProactive.getChapterIndex(),
                    lastProactive.getParagraphIndex(),
                    chapter,
                    paragraph);
            if (steps < minParagraphGap) {
                return silence(SilenceReason.PARAGRAPH_GAP, minCooldownMs);
            }
        }

        // 4. Min wall-clock since last proactive
        if (lastProactive != null && lastProactive.getCreatedAt() != null && minCooldownMs > 0) {
            long elapsedMs = Math.max(0L, java.time.Duration.between(lastProactive.getCreatedAt(), now).toMillis());
            if (elapsedMs < minCooldownMs) {
                return silence(SilenceReason.COOLDOWN, minCooldownMs - elapsedMs);
            }
        }

        // 5. Max comments per chapter / hour
        int maxPerChapter = Math.max(0, properties.getMaxCommentsPerChapter());
        if (maxPerChapter > 0) {
            long chapterCount = messageRepository.countByOwnerKeyAndBookIdAndPersonaIdAndKindAndChapterIndex(
                    ownerKey, bookId, personaId, ReadingBuddyMemoryService.KIND_PROACTIVE, chapter);
            if (chapterCount >= maxPerChapter) {
                return silence(SilenceReason.RATE_CAP, minCooldownMs > 0 ? minCooldownMs : 60_000L);
            }
        }

        int maxPerHour = Math.max(0, properties.getMaxCommentsPerHour());
        if (maxPerHour > 0) {
            LocalDateTime hourAgo = now.minusHours(1);
            long hourCount = messageRepository
                    .countByOwnerKeyAndBookIdAndPersonaIdAndKindAndCreatedAtGreaterThanEqual(
                            ownerKey, bookId, personaId, ReadingBuddyMemoryService.KIND_PROACTIVE, hourAgo);
            if (hourCount >= maxPerHour) {
                long nextMs = hourlyCapRetryMs(ownerKey, bookId, personaId, hourAgo, now);
                return silence(SilenceReason.RATE_CAP, nextMs);
            }
        }

        // 6. Post-chat paragraph gap
        int postChatGap = Math.max(0, properties.getPostChatParagraphGap());
        if (postChatGap > 0) {
            ReadingBuddyMessageEntity lastUserChat = latestUserChat(ownerKey, bookId, personaId);
            if (lastUserChat != null) {
                int steps = paragraphStepsForward(
                        lastUserChat.getChapterIndex(),
                        lastUserChat.getParagraphIndex(),
                        chapter,
                        paragraph);
                if (steps < postChatGap) {
                    return silence(SilenceReason.POST_CHAT_GAP, minCooldownMs > 0 ? minCooldownMs : 45_000L);
                }
            }
        }

        return new TriggerDecision.Eligible(minCooldownMs);
    }

    private ReadingBuddyMessageEntity latestProactive(String ownerKey, String bookId, String personaId) {
        List<ReadingBuddyMessageEntity> page = messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdAndKindOrderByCreatedAtDesc(
                        ownerKey,
                        bookId,
                        personaId,
                        ReadingBuddyMemoryService.KIND_PROACTIVE,
                        PageRequest.of(0, 1));
        return page == null || page.isEmpty() ? null : page.getFirst();
    }

    private ReadingBuddyMessageEntity latestUserChat(String ownerKey, String bookId, String personaId) {
        List<ReadingBuddyMessageEntity> page = messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdAndRoleAndKindOrderByCreatedAtDesc(
                        ownerKey,
                        bookId,
                        personaId,
                        ReadingBuddyMemoryService.ROLE_USER,
                        ReadingBuddyMemoryService.KIND_CHAT,
                        PageRequest.of(0, 1));
        return page == null || page.isEmpty() ? null : page.getFirst();
    }

    private long hourlyCapRetryMs(
            String ownerKey,
            String bookId,
            String personaId,
            LocalDateTime hourAgo,
            LocalDateTime now) {
        List<ReadingBuddyMessageEntity> oldest = messageRepository.findOldestSince(
                ownerKey,
                bookId,
                personaId,
                ReadingBuddyMemoryService.KIND_PROACTIVE,
                hourAgo,
                PageRequest.of(0, 1));
        if (oldest == null || oldest.isEmpty() || oldest.getFirst().getCreatedAt() == null) {
            return 60_000L;
        }
        LocalDateTime unlockAt = oldest.getFirst().getCreatedAt().plusHours(1);
        long remaining = java.time.Duration.between(now, unlockAt).toMillis();
        return Math.max(1_000L, remaining);
    }

    private long defaultCooldownMs(String frequency) {
        return Math.max(0L, properties.minCooldownMsFor(frequency == null ? "rare" : frequency));
    }

    private static TriggerDecision.Silence silence(SilenceReason reason, long nextEligibleAfterMs) {
        return new TriggerDecision.Silence(reason, Math.max(0L, nextEligibleAfterMs));
    }

    private static long toEpochMs(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * Approximate paragraph steps forward from (fromCh, fromPara) to (toCh, toPara).
     * Same chapter: {@code toPara - fromPara}. Later chapter: treated as gap-satisfied
     * (large positive). Earlier chapter: large negative (rewound).
     */
    static int paragraphStepsForward(int fromChapter, int fromParagraph, int toChapter, int toParagraph) {
        if (toChapter > fromChapter) {
            return Integer.MAX_VALUE / 4;
        }
        if (toChapter < fromChapter) {
            return Integer.MIN_VALUE / 4;
        }
        return toParagraph - fromParagraph;
    }

    /**
     * Inputs for hard-filter evaluation (no LLM / no clientHint authority).
     */
    public record TriggerContext(
            String ownerKey,
            String bookId,
            String personaId,
            int chapterIndex,
            int paragraphIndex,
            boolean enabled,
            String frequency,
            Long suppressUntilEpochMs
    ) {
    }

    /**
     * Result of hard-filter evaluation.
     */
    public sealed interface TriggerDecision permits TriggerDecision.Eligible, TriggerDecision.Silence {
        long nextEligibleAfterMs();

        record Eligible(long nextEligibleAfterMs) implements TriggerDecision {
        }

        record Silence(SilenceReason reason, long nextEligibleAfterMs) implements TriggerDecision {
        }
    }
}
