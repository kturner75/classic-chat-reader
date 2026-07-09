package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private ReadingBuddyMemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new ReadingBuddyMemoryService(
                messageRepository, memoryRepository, new ReadingBuddyProperties());
    }

    @Test
    void persistProactive_returnsExistingWhenAlreadyPresent() {
        ReadingBuddyMessageEntity existing = message("first", "kept first");
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                "owner", "book-1", "humorist", "3:12"))
                .thenReturn(Optional.of(existing));

        ReadingBuddyMessageEntity result = memoryService.persistProactiveComment(
                "owner", "book-1", "humorist", "second attempt", 3, 12);

        assertSame(existing, result);
        verify(messageRepository, times(0)).save(any());
    }

    @Test
    void persistProactive_onUniqueViolation_keepsFirstRow() {
        ReadingBuddyMessageEntity winner = message("winner", "first comment wins");
        AtomicInteger lookups = new AtomicInteger(0);
        when(messageRepository.findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
                eq("owner"), eq("book-1"), eq("humorist"), eq("3:12")))
                .thenAnswer(inv -> {
                    // First call: empty (pre-check). Second call after race: winner.
                    if (lookups.getAndIncrement() == 0) {
                        return Optional.empty();
                    }
                    return Optional.of(winner);
                });
        when(messageRepository.save(any(ReadingBuddyMessageEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uk_rbm_proactive_position"));

        ReadingBuddyMessageEntity result = memoryService.persistProactiveComment(
                "owner", "book-1", "humorist", "loser text", 3, 12);

        assertEquals("winner", result.getId());
        assertEquals("first comment wins", result.getContent());
        verify(messageRepository, times(1)).save(any());
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
}
