package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class ReadingBuddyTriggerPolicyTest {

    @Mock
    private ReadingBuddyMessageRepository messageRepository;

    private ReadingBuddyProperties properties;
    private ReadingBuddyTriggerPolicy policy;

    private final LocalDateTime now = LocalDateTime.of(2026, 7, 8, 12, 0, 0);

    @BeforeEach
    void setUp() {
        properties = new ReadingBuddyProperties();
        properties.getMinParagraphGap().setRare(8);
        properties.getMinCooldownMs().setRare(180_000L);
        properties.setMaxCommentsPerChapter(6);
        properties.setMaxCommentsPerHour(12);
        properties.setPostChatParagraphGap(4);
        policy = new ReadingBuddyTriggerPolicy(messageRepository, properties);
    }

    @Test
    void suppressed_whenDisabled() {
        ReadingBuddyTriggerPolicy.TriggerDecision decision = policy.evaluate(baseContext(false), now);
        ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence =
                assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Silence.class, decision);
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.SUPPRESSED, silence.reason());
        verify(messageRepository, never())
                .findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(any(), any(), any(), any());
    }

    @Test
    void suppressed_whenSuppressUntilFuture() {
        long suppressUntil = now.toInstant(ZoneOffset.UTC).toEpochMilli() + 45 * 60_000L;
        ReadingBuddyTriggerPolicy.TriggerContext ctx = new ReadingBuddyTriggerPolicy.TriggerContext(
                "owner", "book-1", "humorist", 3, 12, true, "rare", suppressUntil);

        ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence =
                assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Silence.class, policy.evaluate(ctx, now));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.SUPPRESSED, silence.reason());
        assertTrue(silence.nextEligibleAfterMs() > 0);
    }

    @Test
    void alreadyCommented_whenProactiveExistsAtPosition() {
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                "owner", "book-1", "humorist", "3:12"))
                .thenReturn(Optional.of(proactive(3, 12, now.minusMinutes(10))));

        ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence =
                assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Silence.class,
                        policy.evaluate(baseContext(true), now));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.ALREADY_COMMENTED, silence.reason());
    }

    @Test
    void paragraphGap_whenNotEnoughAdvances() {
        stubNoExistingAtPosition();
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndKindOrderByCreatedAtDesc(
                eq("owner"), eq("book-1"), eq("humorist"), eq("proactive"), any(Pageable.class)))
                .thenReturn(List.of(proactive(3, 5, now.minusMinutes(30))));

        // rare min gap = 8; at para 12 from 5 is only 7 steps
        ReadingBuddyTriggerPolicy.TriggerContext ctx = new ReadingBuddyTriggerPolicy.TriggerContext(
                "owner", "book-1", "humorist", 3, 12, true, "rare", null);

        ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence =
                assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Silence.class, policy.evaluate(ctx, now));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.PARAGRAPH_GAP, silence.reason());
    }

    @Test
    void cooldown_whenWallClockTooSoon() {
        stubNoExistingAtPosition();
        // Far enough paragraphs (gap 10 >= 8) but only 30s ago (cooldown 180s)
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndKindOrderByCreatedAtDesc(
                eq("owner"), eq("book-1"), eq("humorist"), eq("proactive"), any(Pageable.class)))
                .thenReturn(List.of(proactive(3, 2, now.minusSeconds(30))));

        ReadingBuddyTriggerPolicy.TriggerContext ctx = new ReadingBuddyTriggerPolicy.TriggerContext(
                "owner", "book-1", "humorist", 3, 12, true, "rare", null);

        ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence =
                assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Silence.class, policy.evaluate(ctx, now));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.COOLDOWN, silence.reason());
        assertTrue(silence.nextEligibleAfterMs() > 100_000L);
    }

    @Test
    void rateCap_whenChapterLimitReached() {
        stubNoExistingAtPosition();
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndKindOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), eq("proactive"), any(Pageable.class)))
                .thenReturn(List.of());
        when(messageRepository.countByOwnerKeyAndBookIdAndPersonaIdAndKindAndChapterIndex(
                "owner", "book-1", "humorist", "proactive", 3))
                .thenReturn(6L);

        ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence =
                assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Silence.class,
                        policy.evaluate(baseContext(true), now));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.RATE_CAP, silence.reason());
    }

    @Test
    void rateCap_whenHourlyLimitReached_usesOldestInWindowForRetry() {
        stubNoExistingAtPosition();
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndKindOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), eq("proactive"), any(Pageable.class)))
                .thenReturn(List.of());
        when(messageRepository.countByOwnerKeyAndBookIdAndPersonaIdAndKindAndChapterIndex(
                anyString(), anyString(), anyString(), eq("proactive"), anyInt()))
                .thenReturn(0L);
        when(messageRepository.countByOwnerKeyAndBookIdAndPersonaIdAndKindAndCreatedAtGreaterThanEqual(
                eq("owner"), eq("book-1"), eq("humorist"), eq("proactive"), any()))
                .thenReturn(12L);

        LocalDateTime oldestAt = now.minusMinutes(20);
        ReadingBuddyMessageEntity oldest = proactive(1, 0, oldestAt);
        when(messageRepository.findOldestSince(
                eq("owner"), eq("book-1"), eq("humorist"), eq("proactive"), any(), any(Pageable.class)))
                .thenReturn(List.of(oldest));

        ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence =
                assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Silence.class,
                        policy.evaluate(baseContext(true), now));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.RATE_CAP, silence.reason());
        // Oldest at now-20m unlocks at now+40m → ~40 minutes remaining
        assertTrue(silence.nextEligibleAfterMs() > 30 * 60_000L);
        assertTrue(silence.nextEligibleAfterMs() <= 40 * 60_000L);
    }

    @Test
    void postChatGap_whenTooSoonAfterUserChat() {
        stubNoExistingAtPosition();
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndKindOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), eq("proactive"), any(Pageable.class)))
                .thenReturn(List.of());
        when(messageRepository.countByOwnerKeyAndBookIdAndPersonaIdAndKindAndChapterIndex(
                anyString(), anyString(), anyString(), eq("proactive"), anyInt()))
                .thenReturn(0L);
        when(messageRepository.countByOwnerKeyAndBookIdAndPersonaIdAndKindAndCreatedAtGreaterThanEqual(
                anyString(), anyString(), anyString(), eq("proactive"), any()))
                .thenReturn(0L);

        ReadingBuddyMessageEntity userChat = new ReadingBuddyMessageEntity();
        userChat.setRole("user");
        userChat.setKind("chat");
        userChat.setChapterIndex(3);
        userChat.setParagraphIndex(10);
        userChat.setCreatedAt(now.minusMinutes(1));
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndRoleAndKindOrderByCreatedAtDesc(
                eq("owner"), eq("book-1"), eq("humorist"), eq("user"), eq("chat"), any(Pageable.class)))
                .thenReturn(List.of(userChat));

        // Only 2 steps from chat at 10 → current 12; need 4
        ReadingBuddyTriggerPolicy.TriggerDecision.Silence silence =
                assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Silence.class,
                        policy.evaluate(baseContext(true), now));
        assertEquals(ReadingBuddyTriggerPolicy.SilenceReason.POST_CHAT_GAP, silence.reason());
    }

    @Test
    void eligible_whenNoHistoryAndEnabled() {
        stubNoExistingAtPosition();
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndKindOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), eq("proactive"), any(Pageable.class)))
                .thenReturn(List.of());
        when(messageRepository.countByOwnerKeyAndBookIdAndPersonaIdAndKindAndChapterIndex(
                anyString(), anyString(), anyString(), eq("proactive"), anyInt()))
                .thenReturn(0L);
        when(messageRepository.countByOwnerKeyAndBookIdAndPersonaIdAndKindAndCreatedAtGreaterThanEqual(
                anyString(), anyString(), anyString(), eq("proactive"), any()))
                .thenReturn(0L);
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndRoleAndKindOrderByCreatedAtDesc(
                anyString(), anyString(), anyString(), eq("user"), eq("chat"), any(Pageable.class)))
                .thenReturn(List.of());

        ReadingBuddyTriggerPolicy.TriggerDecision decision = policy.evaluate(baseContext(true), now);
        assertInstanceOf(ReadingBuddyTriggerPolicy.TriggerDecision.Eligible.class, decision);
        assertEquals(180_000L, decision.nextEligibleAfterMs());
    }

    @Test
    void paragraphStepsForward_chapterJumpSatisfiesGap() {
        assertTrue(ReadingBuddyTriggerPolicy.paragraphStepsForward(1, 50, 2, 0) >= 8);
        assertEquals(7, ReadingBuddyTriggerPolicy.paragraphStepsForward(3, 5, 3, 12));
    }

    private void stubNoExistingAtPosition() {
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    private ReadingBuddyTriggerPolicy.TriggerContext baseContext(boolean enabled) {
        return new ReadingBuddyTriggerPolicy.TriggerContext(
                "owner", "book-1", "humorist", 3, 12, enabled, "rare", null);
    }

    private static ReadingBuddyMessageEntity proactive(int chapter, int paragraph, LocalDateTime createdAt) {
        ReadingBuddyMessageEntity entity = new ReadingBuddyMessageEntity();
        entity.setRole("buddy");
        entity.setKind("proactive");
        entity.setChapterIndex(chapter);
        entity.setParagraphIndex(paragraph);
        entity.setProactivePositionKey(chapter + ":" + paragraph);
        entity.setContent("prior");
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
