package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ReadingBuddyMemoryRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real JPA unique-index behaviour for proactive position (H2). Uses NOT_SUPPORTED so
 * REQUIRES_NEW inserts commit independently of a wrapping test transaction.
 */
@DataJpaTest
@Import({
        ReadingBuddyMemoryService.class,
        ReadingBuddyProperties.class,
        ReadingBuddyMetricsService.class,
        ReadingBuddyProactivePersistIntegrationTest.StubLlmConfig.class
})
class ReadingBuddyProactivePersistIntegrationTest {

    @TestConfiguration
    static class StubLlmConfig {
        @Bean(name = "chatLlmProvider")
        LlmProvider chatLlmProvider() {
            return new LlmProvider() {
                @Override
                public String generate(String prompt, LlmOptions options) {
                    return "stub summary";
                }

                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public String getProviderName() {
                    return "stub";
                }
            };
        }
    }

    @Autowired
    private ReadingBuddyMemoryService memoryService;

    @Autowired
    private ReadingBuddyMessageRepository messageRepository;

    @Autowired
    private ReadingBuddyMemoryRepository memoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sequentialSecondPersist_keepsFirstRow() {
        BookEntity book = bookRepository.saveAndFlush(new BookEntity("Pride", "Austen", "manual"));

        ReadingBuddyMemoryService.ProactivePersistResult first = memoryService.persistProactiveComment(
                "owner-A", book.getId(), "humorist", "first wins", 3, 12);
        assertTrue(first.inserted());

        ReadingBuddyMemoryService.ProactivePersistResult second = memoryService.persistProactiveComment(
                "owner-A", book.getId(), "humorist", "second loses", 3, 12);
        assertFalse(second.inserted());
        assertEquals(first.message().getId(), second.message().getId());
        assertEquals("first wins", second.message().getContent());

        long count = messageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                "owner-A", book.getId(), "humorist");
        assertEquals(1, count);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void chatTurn_equalTimestamps_ordersUserBeforeBuddyByChronologySequence() {
        BookEntity book = bookRepository.saveAndFlush(new BookEntity("Mansfield Park", "Austen", "manual"));
        ReadingBuddyMemoryService.ChatTurn turn = memoryService.persistChatTurn(
                "owner-chronology",
                book.getId(),
                "close_reader",
                "reader message",
                "buddy reply",
                1,
                2);

        LocalDateTime tiedTimestamp = LocalDateTime.of(2026, 7, 22, 8, 0);
        turn.userMessage().setCreatedAt(tiedTimestamp);
        turn.buddyMessage().setCreatedAt(tiedTimestamp);
        messageRepository.saveAllAndFlush(List.of(turn.buddyMessage(), turn.userMessage()));

        List<ReadingBuddyMessageEntity> ordered = messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
                        "owner-chronology", book.getId(), "close_reader");

        assertEquals(List.of("user", "buddy"),
                ordered.stream().map(ReadingBuddyMessageEntity::getRole).toList());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPersist_samePosition_singleRow() throws Exception {
        BookEntity book = bookRepository.saveAndFlush(new BookEntity("Emma", "Austen", "manual"));
        String bookId = book.getId();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReadingBuddyMemoryService.ProactivePersistResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return memoryService.persistProactiveComment(
                        "owner-B",
                        bookId,
                        "humorist",
                        "comment-from-" + idx,
                        1,
                        5);
            }));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();

        List<ReadingBuddyMemoryService.ProactivePersistResult> results = new ArrayList<>();
        for (Future<ReadingBuddyMemoryService.ProactivePersistResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long inserted = results.stream().filter(ReadingBuddyMemoryService.ProactivePersistResult::inserted).count();
        assertEquals(1, inserted, "exactly one insert should succeed");

        String winnerId = results.getFirst().message().getId();
        for (ReadingBuddyMemoryService.ProactivePersistResult r : results) {
            assertEquals(winnerId, r.message().getId());
        }

        List<ReadingBuddyMessageEntity> all = messageRepository
                .findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc("owner-B", bookId, "humorist");
        assertEquals(1, all.size());
        assertEquals("1:5", all.getFirst().getProactivePositionKey());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentFirstMessages_differentPositions_recoverMemoryRowRace() throws Exception {
        BookEntity book = bookRepository.saveAndFlush(new BookEntity("Persuasion", "Austen", "manual"));
        String bookId = book.getId();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReadingBuddyMemoryService.ProactivePersistResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int paragraphIndex = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return memoryService.persistProactiveComment(
                        "owner-memory-race",
                        bookId,
                        "close_reader",
                        "comment-" + paragraphIndex,
                        1,
                        paragraphIndex);
            }));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();

        for (Future<ReadingBuddyMemoryService.ProactivePersistResult> future : futures) {
            assertTrue(future.get(10, TimeUnit.SECONDS).inserted());
        }
        pool.shutdownNow();

        assertEquals(threads, messageRepository.countByOwnerKeyAndBookIdAndPersonaId(
                "owner-memory-race", bookId, "close_reader"));
        assertTrue(memoryRepository.findByOwnerKeyAndBookIdAndPersonaId(
                "owner-memory-race", bookId, "close_reader").isPresent());
    }
}
