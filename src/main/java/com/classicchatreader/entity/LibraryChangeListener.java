package com.classicchatreader.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts writes to the rows behind the library listing (books, chapters, covers) so the
 * in-memory listing knows when to reload. Counts once when the write is flushed and again after
 * commit: a listing reloaded between the two would otherwise cache data the commit then replaced.
 */
public class LibraryChangeListener {

    private static final AtomicLong GENERATION = new AtomicLong();

    public static long generation() {
        return GENERATION.get();
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    void onChange(Object entity) {
        GENERATION.incrementAndGet();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    GENERATION.incrementAndGet();
                }
            });
        }
    }
}
