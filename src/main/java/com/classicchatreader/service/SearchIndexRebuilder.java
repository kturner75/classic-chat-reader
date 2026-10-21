package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

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
        List<BookEntity> books = bookRepository.findAll();
        int[] paragraphCount = {0};
        searchService.rebuild(sink -> {
            for (BookEntity book : books) {
                List<SearchService.ParagraphText> paragraphs = paragraphRepository.findTextByBookId(book.getId());
                paragraphCount[0] += paragraphs.size();
                sink.addBook(book.getId(), book.getTitle(), book.getAuthor(), paragraphs);
            }
        });
        log.info("Search index rebuilt: {} books, {} paragraphs in {} ms",
                books.size(), paragraphCount[0], (System.nanoTime() - started) / 1_000_000);
        return books.size();
    }
}
