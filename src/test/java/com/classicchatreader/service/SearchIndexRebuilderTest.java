package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
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
import org.springframework.test.context.transaction.TestTransaction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The startup rebuild: right documents, and one paragraph query per book rather than per chapter. */
@DataJpaTest
@Import({SearchIndexRebuilder.class, SearchService.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class SearchIndexRebuilderTest {

    @Autowired private SearchIndexRebuilder rebuilder;
    @Autowired private SearchService searchService;
    @Autowired private BookRepository bookRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void indexesEveryParagraphWithOneQueryPerBook() throws Exception {
        String[] titles = {"Emma", "Persuasion", "Northanger Abbey"};
        for (String title : titles) {
            BookEntity book = new BookEntity(title, "Austen, Jane", "gutenberg");
            for (int c = 0; c < 4; c++) {
                ChapterEntity chapter = new ChapterEntity(c, "CHAPTER " + (c + 1));
                chapter.setBook(book);
                for (int p = 0; p < 3; p++) {
                    ParagraphEntity paragraph = new ParagraphEntity(p,
                            title.replace(" ", "") + "word chapter" + c + " paragraph" + p);
                    paragraph.setChapter(chapter);
                    chapter.getParagraphs().add(paragraph);
                }
                book.getChapters().add(chapter);
            }
            bookRepository.save(book);
        }
        TestTransaction.flagForCommit();
        TestTransaction.end();

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
            assertEquals(3, rebuilder.rebuild());
            assertEquals(1 + titles.length, stats.getPrepareStatementCount(), "books + one paragraph query per book");

            assertEquals(12, searchService.search("NorthangerAbbeyword", 50).size());
            BookEntity persuasion = bookRepository.findAll().stream()
                    .filter(b -> b.getTitle().equals("Persuasion")).findFirst().orElseThrow();
            List<SearchService.SearchResult> hits = searchService.search("paragraph2", persuasion.getId(), 50);
            assertEquals(4, hits.size());
            assertTrue(hits.stream().allMatch(hit -> hit.paragraphIndex() == 2));
    }

    /** The books were committed, so remove them even when an assertion fails. */
    @AfterEach
    void clean() {
        if (TestTransaction.isActive()) {
            TestTransaction.end();
        }
        TestTransaction.start();
        bookRepository.deleteAll();
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }
}
