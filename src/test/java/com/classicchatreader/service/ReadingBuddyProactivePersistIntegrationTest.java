package com.classicchatreader.service;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ReadingBuddyMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
@Import({ReadingBuddyMemoryService.class, ReadingBuddyProperties.class})
class ReadingBuddyProactivePersistIntegrationTest {

    @Autowired
    private ReadingBuddyMemoryService memoryService;

    @Autowired
    private ReadingBuddyMessageRepository messageRepository;

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
}
