package com.classicchatreader.service;

import com.classicchatreader.model.ParagraphText;
import com.classicchatreader.service.SearchService.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchServiceTest {

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchService();
    }

    @Test
    void searchBook_byTitle_returnsMatch() throws Exception {
        searchService.indexBook("pride-and-prejudice", "Pride and Prejudice", "Jane Austen");

        List<SearchResult> results = searchService.search("Pride", 10);

        assertEquals(1, results.size());
        assertEquals("book", results.get(0).type());
        assertEquals("pride-and-prejudice", results.get(0).bookId());
        assertEquals("Pride and Prejudice", results.get(0).title());
    }

    @Test
    void searchBook_byAuthor_returnsMatch() throws Exception {
        searchService.indexBook("moby-dick", "Moby Dick", "Herman Melville");

        List<SearchResult> results = searchService.search("Melville", 10);

        assertEquals(1, results.size());
        assertEquals("moby-dick", results.get(0).bookId());
    }

    @Test
    void searchParagraph_returnsMatchWithIndex() throws Exception {
        searchService.indexParagraph("moby-dick", "ch1", 0, "Call me Ishmael.");
        searchService.indexParagraph("moby-dick", "ch1", 1, "Some years ago.");

        List<SearchResult> results = searchService.search("Ishmael", 10);

        assertEquals(1, results.size());
        SearchResult result = results.get(0);
        assertEquals("paragraph", result.type());
        assertEquals("moby-dick", result.bookId());
        assertEquals("ch1", result.chapterId());
        assertEquals(0, result.paragraphIndex());
        assertNotNull(result.snippet());
        assertTrue(result.snippet().contains("Ishmael"));
    }

    @Test
    void searchParagraph_returnsMultipleMatches() throws Exception {
        searchService.indexParagraph("book1", "ch1", 0, "The quick brown fox.");
        searchService.indexParagraph("book1", "ch1", 1, "The lazy dog sleeps.");
        searchService.indexParagraph("book2", "ch1", 0, "The sun rises early.");

        List<SearchResult> results = searchService.search("The", 10);

        assertEquals(3, results.size());
    }

    @Test
    void search_withLimit_respectsMaxResults() throws Exception {
        searchService.indexParagraph("book1", "ch1", 0, "Word one.");
        searchService.indexParagraph("book1", "ch1", 1, "Word two.");
        searchService.indexParagraph("book1", "ch1", 2, "Word three.");

        List<SearchResult> results = searchService.search("Word", 2);

        assertEquals(2, results.size());
    }

    @Test
    void search_noMatches_returnsEmptyList() throws Exception {
        searchService.indexBook("moby-dick", "Moby Dick", "Herman Melville");

        List<SearchResult> results = searchService.search("nonexistent", 10);

        assertTrue(results.isEmpty());
    }

    @Test
    void search_mixedResults_returnsBooksAndParagraphs() throws Exception {
        searchService.indexBook("moby-dick", "Moby Dick", "Herman Melville");
        searchService.indexParagraph("moby-dick", "ch1", 0, "Call me Ishmael from Moby Dick.");

        List<SearchResult> results = searchService.search("Moby", 10);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> "book".equals(r.type())));
        assertTrue(results.stream().anyMatch(r -> "paragraph".equals(r.type())));
    }

    @Test
    void searchParagraph_longContent_truncatesSnippet() throws Exception {
        String longContent = "This is a very long paragraph that contains more than one hundred characters and should be truncated when returned as a snippet in search results.";
        searchService.indexParagraph("book1", "ch1", 0, longContent);

        List<SearchResult> results = searchService.search("paragraph", 10);

        assertEquals(1, results.size());
        assertTrue(results.get(0).snippet().contains("paragraph"));
        assertTrue(results.get(0).snippet().startsWith("...") || results.get(0).snippet().endsWith("..."));
    }

    @Test
    void search_withBookId_filtersToSpecificBook() throws Exception {
        searchService.indexParagraph("book1", "ch1", 0, "The quick brown fox.");
        searchService.indexParagraph("book2", "ch1", 0, "The lazy dog sleeps.");

        List<SearchResult> results = searchService.search("The", "book1", 10);

        assertEquals(1, results.size());
        assertEquals("book1", results.get(0).bookId());
    }

    @Test
    void search_withBookId_returnsEmptyWhenNoMatchInBook() throws Exception {
        searchService.indexParagraph("book1", "ch1", 0, "The quick brown fox.");
        searchService.indexParagraph("book2", "ch1", 0, "The lazy dog sleeps.");

        List<SearchResult> results = searchService.search("fox", "book2", 10);

        assertTrue(results.isEmpty());
    }

    @Test
    void search_withChapterId_filtersToSpecificChapter() throws Exception {
        searchService.indexParagraph("book1", "ch1", 0, "The quick brown fox.");
        searchService.indexParagraph("book1", "ch2", 0, "The sly red fox.");

        List<SearchResult> results = searchService.search("fox", "book1", "ch2", 10);

        assertEquals(1, results.size());
        assertEquals("ch2", results.get(0).chapterId());
    }

    @Test
    void indexBookContent_replacesEarlierCopyOfTheSameBook() throws Exception {
        searchService.indexBookContent("moby-dick", "Moby Dick", "Herman Melville",
                List.of(new ParagraphText("ch1", 0, "Call me Ishmael.")));
        searchService.indexBookContent("moby-dick", "Moby Dick", "Herman Melville",
                List.of(new ParagraphText("ch1", 0, "Call me Ishmael."),
                        new ParagraphText("ch1", 1, "Ishmael again.")));

        assertEquals(2, searchService.search("Ishmael", 10).size(), "re-saving a book does not duplicate it");
        assertEquals(1, searchService.search("Melville", 10).size());
    }

    @Test
    void rebuild_replacesTheWholeIndexAndDropsBooksNoLongerFed() throws Exception {
        searchService.indexBook("stale-book", "Stale Book", "Nobody");

        searchService.rebuild(sink -> {
            sink.addBook("emma", "Emma", "Jane Austen",
                    List.of(new ParagraphText("ch1", 0, "Emma Woodhouse, handsome, clever, and rich.")));
            sink.addBook("persuasion", "Persuasion", "Jane Austen",
                    List.of(new ParagraphText("ch3", 4, "Anne Elliot was nobody with either father or sister.")));
        });

        assertTrue(searchService.search("Stale", 10).isEmpty());
        assertEquals(2, searchService.search("Austen", 10).size());
        SearchResult anne = searchService.search("Elliot", "persuasion", 10).get(0);
        assertEquals("ch3", anne.chapterId());
        assertEquals(4, anne.paragraphIndex());
    }

    @Test
    void rebuild_isInvisibleToSearchesUntilItCommits() throws Exception {
        searchService.indexBook("emma", "Emma", "Jane Austen");

        int[] midRebuildHits = {-1};
        searchService.rebuild(sink -> {
            try {
                midRebuildHits[0] = searchService.search("Emma", 10).size();
            } catch (org.apache.lucene.queryparser.classic.ParseException e) {
                throw new IllegalStateException(e);
            }
            sink.addBook("emma", "Emma", "Jane Austen", List.of());
        });
        assertEquals(1, midRebuildHits[0], "mid-rebuild searches see the previous index");

        assertEquals(1, searchService.search("Emma", 10).size());
    }

    /** Runs a rebuild that pauses inside its feed until {@code release} opens, so a live write can race it. */
    private Thread pausedRebuild(java.util.concurrent.CountDownLatch inFeed, java.util.concurrent.CountDownLatch release,
                                 String bookId, String title) {
        Thread rebuild = new Thread(() -> {
            try {
                searchService.rebuild(sink -> {
                    inFeed.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    sink.addBook(bookId, title, "Jane Austen", List.of());
                });
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        rebuild.start();
        return rebuild;
    }

    @Test
    void bookSavedDuringARebuildIsStillSearchableAfterIt() throws Exception {
        var inFeed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread rebuild = pausedRebuild(inFeed, release, "emma", "Emma");
        inFeed.await();

        Thread save = new Thread(() -> {
            try {
                searchService.indexBookContent("persuasion", "Persuasion", "Jane Austen", List.of());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        save.start();
        Thread.sleep(100); // the save is now waiting on the rebuild
        release.countDown();
        rebuild.join(5000);
        save.join(5000);

        assertEquals(1, searchService.search("Persuasion", 10).size(), "a save racing a rebuild is not wiped");
        assertEquals(1, searchService.search("Emma", 10).size());
    }

    @Test
    void bookDeletedDuringARebuildStaysDeleted() throws Exception {
        var inFeed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread rebuild = pausedRebuild(inFeed, release, "emma", "Emma");
        inFeed.await();

        Thread delete = new Thread(() -> {
            try {
                searchService.deleteByBookId("emma");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        delete.start();
        Thread.sleep(100);
        release.countDown();
        rebuild.join(5000);
        delete.join(5000);

        assertTrue(searchService.search("Emma", 10).isEmpty(), "a delete racing a rebuild is not undone");
    }

    @Test
    void failedRebuildKeepsThePreviousIndexAndTheServiceUsable() throws Exception {
        searchService.indexBook("emma", "Emma", "Jane Austen");

        assertThrows(IllegalStateException.class, () -> searchService.rebuild(sink -> {
            sink.addBook("half", "Half Built", "Nobody", List.of());
            throw new IllegalStateException("database went away");
        }));

        assertEquals(1, searchService.search("Emma", 10).size());
        assertTrue(searchService.search("Half", 10).isEmpty());
        searchService.indexBookContent("persuasion", "Persuasion", "Jane Austen", List.of());
        assertEquals(1, searchService.search("Persuasion", 10).size(), "the writer was reopened");
        assertEquals(1, searchService.search("Emma", 10).size(), "the failed rebuild's deleteAll was not published");
    }
}
