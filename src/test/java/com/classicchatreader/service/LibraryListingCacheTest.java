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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Real commits (no test transaction): the cache must see writes only once they are committed. */
@DataJpaTest
@Import({BookStorageService.class, LibraryListingCache.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LibraryListingCacheTest {

    @Autowired private LibraryListingCache cache;
    @Autowired private BookRepository bookRepository;
    @Autowired private BookCoverRepository bookCoverRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @MockitoBean private SearchService searchService;
    @MockitoBean private MlaCitationFormatter mlaCitationFormatter;
    @MockitoBean private CuratedCatalogService curatedCatalogService;

    @AfterEach
    void clean() {
        bookCoverRepository.deleteAll();
        bookRepository.deleteAll();
    }

    private BookEntity saveBook(String title) {
        BookEntity book = new BookEntity(title, "Austen, Jane", "gutenberg");
        ChapterEntity chapter = new ChapterEntity(0, "CHAPTER I");
        chapter.setBook(book);
        book.getChapters().add(chapter);
        return bookRepository.save(book);
    }

    private Statistics stats() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void repeatReadsCostNoQueries() {
        saveBook("Northanger Abbey");
        assertEquals(1, cache.books().size());

        stats().clear();
        for (int i = 0; i < 5; i++) {
            assertEquals("Northanger Abbey", cache.books().get(0).title());
        }
        assertEquals(0, stats().getPrepareStatementCount());
    }

    @Test
    void committedBookAndCoverChangesShowOnTheNextRead() {
        BookEntity first = saveBook("Northanger Abbey");
        assertEquals(1, cache.books().size());

        saveBook("Persuasion");
        assertEquals(List.of("Northanger Abbey", "Persuasion"),
                cache.books().stream().map(Book::title).sorted().toList());

        BookCoverEntity cover = new BookCoverEntity(first);
        cover.setImageFilename("cover.png");
        cover.setStatus(IllustrationStatus.COMPLETED);
        cover.setCompletedAt(LocalDateTime.of(2026, 10, 21, 9, 0, 5));
        bookCoverRepository.save(cover);
        Book withCover = cache.books().stream().filter(b -> b.id().equals(first.getId())).findFirst().orElseThrow();
        assertEquals("/api/library/" + first.getId() + "/cover?v=20261021090005", withCover.coverUrl());

        bookRepository.deleteById(bookRepository.findAll().stream()
                .filter(b -> b.getTitle().equals("Persuasion")).findFirst().orElseThrow().getId());
        assertEquals(List.of("Northanger Abbey"), cache.books().stream().map(Book::title).toList());
    }

    @Test
    void curatedCatalogChangeReloads() {
        saveBook("Emma");
        when(curatedCatalogService.generation()).thenReturn(1L);
        cache.books();

        stats().clear();
        when(curatedCatalogService.generation()).thenReturn(2L);
        cache.books();
        assertTrue(stats().getPrepareStatementCount() > 0, "a curated-catalog change reloads the listing");
    }
}
