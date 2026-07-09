package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrent position uniqueness: first insert wins; unique violation reloads winner.
 */
@ExtendWith(MockitoExtension.class)
class ReadingBuddyProactiveRaceTest {

    @Mock
    private ReadingBuddyMessageRepository messageRepository;

    @Mock
    private ReadingBuddyMemoryRepository memoryRepository;

    @Mock
    private LlmProvider chatProvider;

    @Mock
    private ReadingBuddyMetricsService metricsService;

    private ReadingBuddyMemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new ReadingBuddyMemoryService(
                messageRepository,
                memoryRepository,
                new ReadingBuddyProperties(),
                chatProvider,
                metricsService,
                new ImmediateTransactionManager());
    }

    @Test
    void persistProactive_returnsExistingWhenAlreadyPresent() {
        ReadingBuddyMessageEntity existing = message("first", "kept first");
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                "owner", "book-1", "humorist", "3:12"))
                .thenReturn(Optional.of(existing));

        ReadingBuddyMemoryService.ProactivePersistResult result = memoryService.persistProactiveComment(
                "owner", "book-1", "humorist", "second attempt", 3, 12);

        assertFalse(result.inserted());
        assertEquals("first", result.message().getId());
        verify(messageRepository, times(0)).saveAndFlush(any());
    }

    @Test
    void persistProactive_onUniqueViolation_keepsFirstRowViaNewTxRequery() {
        ReadingBuddyMessageEntity winner = message("winner", "first comment wins");
        AtomicInteger lookups = new AtomicInteger(0);
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                eq("owner"), eq("book-1"), eq("humorist"), eq("3:12")))
                .thenAnswer(inv -> {
                    int n = lookups.getAndIncrement();
                    // 0: outer pre-check empty
                    // 1: nested re-check empty
                    // 2+: recovery re-query after integrity violation → winner
                    if (n < 2) {
                        return Optional.empty();
                    }
                    return Optional.of(winner);
                });
        when(messageRepository.saveAndFlush(any(ReadingBuddyMessageEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uk_rbm_proactive_position"));

        ReadingBuddyMemoryService.ProactivePersistResult result = memoryService.persistProactiveComment(
                "owner", "book-1", "humorist", "loser text", 3, 12);

        assertFalse(result.inserted());
        assertEquals("winner", result.message().getId());
        assertEquals("first comment wins", result.message().getContent());
        verify(messageRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void persistProactive_successfulInsert_flagsInserted() {
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.saveAndFlush(any(ReadingBuddyMessageEntity.class)))
                .thenAnswer(inv -> {
                    ReadingBuddyMessageEntity e = inv.getArgument(0);
                    e.setId("new-1");
                    return e;
                });
        when(memoryRepository.findByOwnerKeyAndBookIdAndPersonaId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(memoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReadingBuddyMemoryService.ProactivePersistResult result = memoryService.persistProactiveComment(
                "owner", "book-1", "humorist", "fresh comment", 3, 12);

        assertTrue(result.inserted());
        assertEquals("new-1", result.message().getId());
        assertEquals("fresh comment", result.message().getContent());
    }

    private static ReadingBuddyMessageEntity message(String id, String content) {
        ReadingBuddyMessageEntity entity = new ReadingBuddyMessageEntity();
        entity.setId(id);
        entity.setOwnerKey("owner");
        entity.setBookId("book-1");
        entity.setPersonaId("humorist");
        entity.setRole("buddy");
        entity.setKind("proactive");
        entity.setContent(content);
        entity.setChapterIndex(3);
        entity.setParagraphIndex(12);
        entity.setProactivePositionKey("3:12");
        entity.setContentHash(ReadingBuddyMessageEntity.computeContentHash("buddy", "proactive", content));
        return entity;
    }

    /**
     * Runs callbacks immediately without a real DB transaction (unit-test stand-in for REQUIRES_NEW).
     */
    static final class ImmediateTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            // no-op
        }
    }
}
