package com.classicchatreader.service;

import com.classicchatreader.model.ParagraphText;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fills the in-memory search index from the database: one query for the books, then one per book
 * for its paragraphs as plain values, all written with a single commit. The old path loaded every
 * chapter's paragraphs separately and committed per paragraph, which took ~15 minutes in prod.
 */
@Service
public class SearchIndexRebuilder {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexRebuilder.class);

    private final BookRepository bookRepository;
    private final ParagraphRepository paragraphRepository;
    private final SearchService searchService;

    public SearchIndexRebuilder(BookRepository bookRepository, ParagraphRepository paragraphRepository,
                                SearchService searchService) {
        this.bookRepository = bookRepository;
        this.paragraphRepository = paragraphRepository;
        this.searchService = searchService;
    }

    /** Returns how many books were indexed. */
    public int rebuild() throws IOException {
        long started = System.nanoTime();
        AtomicInteger bookCount = new AtomicInteger();
        AtomicInteger paragraphCount = new AtomicInteger();
        searchService.rebuild(sink -> {
            // Read the book list under the index write lock: a save or delete that lands after
            // this read waits for the rebuild and then applies, so nothing is lost or resurrected.
            List<BookEntity> books = bookRepository.findAll();
            for (BookEntity book : books) {
                List<ParagraphText> paragraphs = paragraphRepository.findTextByBookId(book.getId());
                paragraphCount.addAndGet(paragraphs.size());
                sink.addBook(book.getId(), book.getTitle(), book.getAuthor(), paragraphs);
            }
            bookCount.set(books.size());
        });
        log.info("Search index rebuilt: {} books, {} paragraphs in {} ms",
                bookCount.get(), paragraphCount.get(), (System.nanoTime() - started) / 1_000_000);
        return bookCount.get();
    }
}
