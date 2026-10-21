package com.classicchatreader.service;

import com.classicchatreader.entity.LibraryChangeListener;
import com.classicchatreader.model.Book;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The library listing ({@code GET /api/library}) held in memory, so landing on the site costs no
 * database queries. Every visitor sees the same books, and the catalog only changes when an
 * operator imports, edits, deletes or re-covers a book.
 *
 * <p>Reloads on the next read after any JPA write to a book, chapter or cover
 * ({@link LibraryChangeListener}) or a curated-catalog change, and at most
 * {@link #SNAPSHOT_TTL_NANOS} after a change made outside JPA. Prod runs one instance; with more,
 * the TTL bounds how stale another instance's copy can be.
 */
@Service
public class LibraryListingCache {

    private static final Logger log = LoggerFactory.getLogger(LibraryListingCache.class);
    private static final long SNAPSHOT_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private record Snapshot(List<Book> books, long libraryGeneration, long curatedGeneration, long loadedAt) {}

    private final BookStorageService bookStorageService;
    private final CuratedCatalogService curatedCatalogService;
    private volatile Snapshot snapshot;

    public LibraryListingCache(BookStorageService bookStorageService, CuratedCatalogService curatedCatalogService) {
        this.bookStorageService = bookStorageService;
        this.curatedCatalogService = curatedCatalogService;
    }

    public List<Book> books() {
        Snapshot current = snapshot;
        long libraryGeneration = LibraryChangeListener.generation();
        long curatedGeneration = curatedCatalogService.generation();
        if (current == null
                || current.libraryGeneration() != libraryGeneration
                || current.curatedGeneration() != curatedGeneration
                || System.nanoTime() - current.loadedAt() > SNAPSHOT_TTL_NANOS) {
            // Generations are read before loading: a write that lands mid-load leaves this copy
            // stale, so the next read reloads instead of serving pre-write rows until the TTL.
            current = new Snapshot(List.copyOf(bookStorageService.getAllBooks()),
                    libraryGeneration, curatedGeneration, System.nanoTime());
            snapshot = current;
        }
        return current.books();
    }

    /** Loads the listing at startup so the first visitor does not pay for it. */
    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        try {
            log.info("Library listing cache warmed with {} books", books().size());
        } catch (RuntimeException e) {
            log.warn("Could not warm the library listing cache; it will load on first request", e);
        }
    }
}
