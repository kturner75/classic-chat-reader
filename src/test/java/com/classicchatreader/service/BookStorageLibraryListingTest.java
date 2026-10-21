package com.classicchatreader.service;

import com.classicchatreader.entity.BookCoverEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.model.Book;
import com.classicchatreader.repository.BookCoverRepository;
import com.classicchatreader.repository.BookRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** GET /api/library must cost a fixed number of queries, not two per book. */
@DataJpaTest
@Import(BookStorageService.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class BookStorageLibraryListingTest {

    @Autowired private BookStorageService bookStorageService;
    @Autowired private BookRepository bookRepository;
    @Autowired private BookCoverRepository bookCoverRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @MockitoBean private SearchService searchService;
    @MockitoBean private MlaCitationFormatter mlaCitationFormatter;
    @MockitoBean private CuratedCatalogService curatedCatalogService;

    @Test
    void listsEveryBookWithChaptersInOrderAndCoversInThreeQueries() {
        for (int i = 0; i < 6; i++) {
            BookEntity book = new BookEntity("Book " + i, "Author " + i, "gutenberg");
            book.setCoverUrl("/fallback-" + i + ".png");
            for (int c = 2; c >= 0; c--) {
                ChapterEntity chapter = new ChapterEntity(c, "Book " + i + " chapter " + c);
                chapter.setBook(book);
                book.getChapters().add(chapter);
            }
            bookRepository.save(book);
            if (i % 2 == 0) {
                BookCoverEntity cover = new BookCoverEntity(book);
                cover.setImageFilename("cover-" + i + ".png");
                cover.setStatus(IllustrationStatus.COMPLETED);
                cover.setCreatedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
                cover.setCompletedAt(LocalDateTime.of(2026, 10, 2, 8, 30, 15));
                bookCoverRepository.save(cover);
            }
        }
        TestTransaction.flagForCommit();
        TestTransaction.end();

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        List<Book> books = bookStorageService.getAllBooks();
        long queries = stats.getPrepareStatementCount();

        assertEquals(6, books.size());
        assertTrue(queries >= 1 && queries <= 3, "expected books + chapter titles + covers, got " + queries + " queries");
        for (Book book : books) {
            int i = Integer.parseInt(book.title().substring(5));
            assertEquals(List.of("Book " + i + " chapter 0", "Book " + i + " chapter 1", "Book " + i + " chapter 2"),
                    book.chapters().stream().map(Book.Chapter::title).toList());
            if (i % 2 == 0) {
                assertEquals("/api/library/" + book.id() + "/cover?v=20261002083015", book.coverUrl());
            } else {
                assertEquals("/fallback-" + i + ".png", book.coverUrl());
            }
        }

        TestTransaction.start();
        bookCoverRepository.deleteAll();
        bookRepository.deleteAll();
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }
}
