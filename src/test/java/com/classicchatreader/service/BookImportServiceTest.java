package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.gutendex.GutenbergContentParser;
import com.classicchatreader.gutendex.GutenbergContentParser.ParsedBook;
import com.classicchatreader.gutendex.GutenbergContentParser.ParsedChapter;
import com.classicchatreader.gutendex.GutendexBook;
import com.classicchatreader.gutendex.GutendexClient;
import com.classicchatreader.gutendex.GutendexResponse;
import com.classicchatreader.model.Book;
import com.classicchatreader.service.BookImportService.ImportResult;
import com.classicchatreader.service.BookImportService.SearchResult;
import com.classicchatreader.service.CuratedCatalogService.CuratedCatalogBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookImportServiceTest {

    @Mock
    private GutendexClient gutendexClient;

    @Mock
    private GutenbergContentParser contentParser;

    @Mock
    private BookStorageService bookStorageService;

    @Mock
    private CuratedCatalogService curatedCatalogService;

    private BookImportService bookImportService;

    @BeforeEach
    void setUp() {
        bookImportService = new BookImportService(gutendexClient, contentParser, bookStorageService, curatedCatalogService, "full");
    }

    @Test
    void searchGutenbergReturnsResults() {
        GutendexBook book = createGutendexBook(1, "Pride and Prejudice", "Jane Austen");
        GutendexResponse response = new GutendexResponse(1, null, null, List.of(book));
        when(gutendexClient.searchBooks("austen")).thenReturn(response);
        when(bookStorageService.existsBySource("gutenberg", "1")).thenReturn(false);

        List<SearchResult> results = bookImportService.searchGutenberg("austen");

        assertEquals(1, results.size());
        assertEquals(1, results.get(0).gutenbergId());
        assertEquals("Pride and Prejudice", results.get(0).title());
        assertEquals("Jane Austen", results.get(0).author());
        assertEquals(List.of("Fiction"), results.get(0).subjects());
        assertEquals(List.of("Classics Bookshelf"), results.get(0).bookshelves());
        assertFalse(results.get(0).alreadyImported());
    }

    @Test
    void searchGutenbergMarksAlreadyImportedBooks() {
        GutendexBook book = createGutendexBook(1, "Pride and Prejudice", "Jane Austen");
        GutendexResponse response = new GutendexResponse(1, null, null, List.of(book));
        when(gutendexClient.searchBooks("austen")).thenReturn(response);
        when(bookStorageService.existsBySource("gutenberg", "1")).thenReturn(true);

        List<SearchResult> results = bookImportService.searchGutenberg("austen");

        assertEquals(1, results.size());
        assertTrue(results.get(0).alreadyImported());
    }

    @Test
    void searchGutenbergFiltersNonEnglishBooks() {
        GutendexBook englishBook = createGutendexBook(1, "Pride and Prejudice", "Jane Austen");
        GutendexBook frenchBook = new GutendexBook(
            2, "Les Misérables",
            List.of(new GutendexBook.Author("Victor Hugo", null, null)),
            List.of(), List.of(), List.of("fr"),
            Map.of("text/html", "http://example.com/book.html"),
            1000
        );
        GutendexResponse response = new GutendexResponse(2, null, null, List.of(englishBook, frenchBook));
        when(gutendexClient.searchBooks("book")).thenReturn(response);
        when(bookStorageService.existsBySource(anyString(), anyString())).thenReturn(false);

        List<SearchResult> results = bookImportService.searchGutenberg("book");

        assertEquals(1, results.size());
        assertEquals("Pride and Prejudice", results.get(0).title());
    }

    @Test
    void searchGutenbergFiltersBooksSansHtml() {
        GutendexBook bookWithHtml = createGutendexBook(1, "Pride and Prejudice", "Jane Austen");
        GutendexBook bookWithoutHtml = new GutendexBook(
            2, "No HTML Book",
            List.of(new GutendexBook.Author("Author", null, null)),
            List.of(), List.of(), List.of("en"),
            Map.of("text/plain", "http://example.com/book.txt"),
            500
        );
        GutendexResponse response = new GutendexResponse(2, null, null, List.of(bookWithHtml, bookWithoutHtml));
        when(gutendexClient.searchBooks("book")).thenReturn(response);
        when(bookStorageService.existsBySource(anyString(), anyString())).thenReturn(false);

        List<SearchResult> results = bookImportService.searchGutenberg("book");

        assertEquals(1, results.size());
        assertEquals("Pride and Prejudice", results.get(0).title());
    }

    @Test
    void searchGutenbergHandlesNullResponse() {
        when(gutendexClient.searchBooks("query")).thenReturn(null);

        List<SearchResult> results = bookImportService.searchGutenberg("query");

        assertTrue(results.isEmpty());
    }

    @Test
    void getPopularBooksReturnsResults() {
        GutendexBook book = createGutendexBook(1, "Moby Dick", "Herman Melville");
        GutendexResponse response = new GutendexResponse(1, null, null, List.of(book));
        when(gutendexClient.getPopularBooks()).thenReturn(response);
        when(bookStorageService.existsBySource(anyString(), anyString())).thenReturn(false);

        List<SearchResult> results = bookImportService.getPopularBooks();

        assertEquals(1, results.size());
        assertEquals("Moby Dick", results.get(0).title());
    }

    @Test
    void getPopularBooksWithPageReturnsResults() {
        GutendexBook book = createGutendexBook(1, "Moby Dick", "Herman Melville");
        GutendexResponse response = new GutendexResponse(1, null, null, List.of(book));
        when(gutendexClient.getPopularBooks(2)).thenReturn(response);
        when(bookStorageService.existsBySource(anyString(), anyString())).thenReturn(false);

        List<SearchResult> results = bookImportService.getPopularBooks(2);

        assertEquals(1, results.size());
        verify(gutendexClient).getPopularBooks(2);
    }

    @Test
    void searchGutenbergSanitizesSubjectAndBookshelfMetadata() {
        GutendexBook book = new GutendexBook(
            5,
            "Metadata Test",
            List.of(new GutendexBook.Author("Author", null, null)),
            Arrays.asList("Adventure stories", " ", "Adventure stories", "Sea stories"),
            Arrays.asList("Classics", null, "Classics", "Travel"),
            List.of("en"),
            Map.of("text/html", "http://example.com/book.html"),
            100
        );
        when(gutendexClient.searchBooks("metadata")).thenReturn(new GutendexResponse(1, null, null, List.of(book)));
        when(bookStorageService.existsBySource("gutenberg", "5")).thenReturn(false);

        List<SearchResult> results = bookImportService.searchGutenberg("metadata");

        assertEquals(1, results.size());
        assertEquals(List.of("Adventure stories", "Sea stories"), results.get(0).subjects());
        assertEquals(List.of("Classics", "Travel"), results.get(0).bookshelves());
    }

    @Test
    void searchGutenbergUsesCuratedCatalogWhenConfigured() {
        bookImportService = new BookImportService(gutendexClient, contentParser, bookStorageService, curatedCatalogService, "curated");
        CuratedCatalogBook curated = new CuratedCatalogBook(
                1342,
                "Pride and Prejudice",
                "Jane Austen",
                100_000,
                List.of("Courtship fiction"),
                List.of("Romance")
        );
        when(curatedCatalogService.search("austen")).thenReturn(List.of(curated));
        when(bookStorageService.existsBySource("gutenberg", "1342")).thenReturn(true);
        when(bookStorageService.findBySource("gutenberg", "1342")).thenReturn(Optional.of(
                new Book("book-1342", "Pride and Prejudice", "Jane Austen", "", "/api/library/book-1342/cover", List.of(), true, true, true)
        ));

        List<SearchResult> results = bookImportService.searchGutenberg("austen");

        assertEquals(1, results.size());
        assertEquals(1342, results.get(0).gutenbergId());
        assertTrue(results.get(0).alreadyImported());
        assertEquals("book-1342", results.get(0).localBookId());
        assertEquals("/api/library/book-1342/cover", results.get(0).coverUrl());
        verify(gutendexClient, never()).searchBooks(anyString());
    }

    @Test
    void getPopularBooksUsesCuratedCatalogWhenConfigured() {
        bookImportService = new BookImportService(gutendexClient, contentParser, bookStorageService, curatedCatalogService, "curated");
        CuratedCatalogBook curated = new CuratedCatalogBook(
                84,
                "Frankenstein",
                "Mary Shelley",
                98_000,
                List.of("Science fiction"),
                List.of("Horror")
        );
        when(curatedCatalogService.getPopularBooks()).thenReturn(List.of(curated));
        when(bookStorageService.existsBySource("gutenberg", "84")).thenReturn(false);

        List<SearchResult> results = bookImportService.getPopularBooks(3);

        assertEquals(1, results.size());
        assertEquals("Frankenstein", results.get(0).title());
        verify(gutendexClient, never()).getPopularBooks(anyInt());
        verify(gutendexClient, never()).getPopularBooks();
    }

    @Test
    void importBookSucceedsForNewBook() {
        GutendexBook gutendexBook = createGutendexBook(1234, "Test Book", "Test Author");
        when(bookStorageService.existsBySource("gutenberg", "1234")).thenReturn(false);
        when(gutendexClient.getBook(1234)).thenReturn(Optional.of(gutendexBook));
        when(gutendexClient.fetchContent(anyString())).thenReturn("<html><body><p>Content</p></body></html>");

        ParsedChapter chapter = new ParsedChapter("Chapter 1", List.of("Paragraph one", "Paragraph two"));
        ParsedBook parsedBook = new ParsedBook(List.of(chapter));
        when(contentParser.parse(anyString())).thenReturn(parsedBook);
        when(curatedCatalogService.isCuratedGutenbergId(1234)).thenReturn(false);

        ArgumentCaptor<BookEntity> bookCaptor = ArgumentCaptor.forClass(BookEntity.class);
        Book savedBook = new Book("book-id", "Test Book", "Test Author", "", null, List.of(), false, false, false);
        when(bookStorageService.saveBook(bookCaptor.capture())).thenReturn(savedBook);

        ImportResult result = bookImportService.importBook(1234);

        assertTrue(result.success());
        assertEquals("book-id", result.bookId());
        assertEquals("Successfully imported", result.message());
        assertEquals(1, result.chapterCount());
        assertEquals(2, result.paragraphCount());
        assertFalse(bookCaptor.getValue().getTtsEnabled());
        assertFalse(bookCaptor.getValue().getIllustrationEnabled());
        assertFalse(bookCaptor.getValue().getCharacterEnabled());
    }

    @Test
    void importBookEnablesFeaturesForCuratedGutenbergTitles() {
        GutendexBook gutendexBook = createGutendexBook(844, "The Importance of Being Earnest", "Oscar Wilde");
        when(bookStorageService.existsBySource("gutenberg", "844")).thenReturn(false);
        when(gutendexClient.getBook(844)).thenReturn(Optional.of(gutendexBook));
        when(gutendexClient.fetchContent(anyString())).thenReturn("<html><body><p>Content</p></body></html>");

        ParsedChapter chapter = new ParsedChapter("Chapter 1", List.of("Paragraph one", "Paragraph two"));
        ParsedBook parsedBook = new ParsedBook(List.of(chapter));
        when(contentParser.parse(anyString())).thenReturn(parsedBook);
        when(curatedCatalogService.isCuratedGutenbergId(844)).thenReturn(true);

        ArgumentCaptor<BookEntity> bookCaptor = ArgumentCaptor.forClass(BookEntity.class);
        Book savedBook = new Book("book-id", "The Importance of Being Earnest", "Oscar Wilde", "", null, List.of(), true, true, true);
        when(bookStorageService.saveBook(bookCaptor.capture())).thenReturn(savedBook);

        ImportResult result = bookImportService.importBook(844);

        assertTrue(result.success());
        assertTrue(bookCaptor.getValue().getTtsEnabled());
        assertTrue(bookCaptor.getValue().getIllustrationEnabled());
        assertTrue(bookCaptor.getValue().getCharacterEnabled());
    }

    @Test
    void importBookFailsIfAlreadyImported() {
        when(bookStorageService.existsBySource("gutenberg", "1234")).thenReturn(true);
        when(bookStorageService.findBySource("gutenberg", "1234"))
            .thenReturn(Optional.of(new Book("existing-id", "Test", "Author", "", null, List.of(), false, false, false)));

        ImportResult result = bookImportService.importBook(1234);

        assertFalse(result.success());
        assertEquals("existing-id", result.bookId());
        assertEquals("Book already imported", result.message());
    }

    @Test
    void importBookFailsIfBookNotFound() {
        when(bookStorageService.existsBySource("gutenberg", "1234")).thenReturn(false);
        when(gutendexClient.getBook(1234)).thenReturn(Optional.empty());

        ImportResult result = bookImportService.importBook(1234);

        assertFalse(result.success());
        assertNull(result.bookId());
        assertEquals("Book not found in Gutenberg", result.message());
    }

    @Test
    void importBookFailsIfNoHtmlVersion() {
        GutendexBook bookWithoutHtml = new GutendexBook(
            1234, "No HTML",
            List.of(new GutendexBook.Author("Author", null, null)),
            List.of(), List.of(), List.of("en"),
            Map.of("text/plain", "http://example.com/book.txt"),
            100
        );
        when(bookStorageService.existsBySource("gutenberg", "1234")).thenReturn(false);
        when(gutendexClient.getBook(1234)).thenReturn(Optional.of(bookWithoutHtml));

        ImportResult result = bookImportService.importBook(1234);

        assertFalse(result.success());
        assertEquals("No HTML version available", result.message());
    }

    @Test
    void importBookFailsIfContentFetchFails() {
        GutendexBook gutendexBook = createGutendexBook(1234, "Test Book", "Test Author");
        when(bookStorageService.existsBySource("gutenberg", "1234")).thenReturn(false);
        when(gutendexClient.getBook(1234)).thenReturn(Optional.of(gutendexBook));
        when(gutendexClient.fetchContent(anyString())).thenThrow(new RuntimeException("Network error"));

        ImportResult result = bookImportService.importBook(1234);

        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to fetch content"));
    }

    @Test
    void importBookFailsIfNoContentParsed() {
        GutendexBook gutendexBook = createGutendexBook(1234, "Test Book", "Test Author");
        when(bookStorageService.existsBySource("gutenberg", "1234")).thenReturn(false);
        when(gutendexClient.getBook(1234)).thenReturn(Optional.of(gutendexBook));
        when(gutendexClient.fetchContent(anyString())).thenReturn("<html><body></body></html>");
        when(contentParser.parse(anyString())).thenReturn(new ParsedBook(List.of()));

        ImportResult result = bookImportService.importBook(1234);

        assertFalse(result.success());
        assertEquals("No content could be parsed", result.message());
    }

    @Test
    void importBookSetsCorrectSourceInfo() {
        GutendexBook gutendexBook = createGutendexBook(1234, "Test Book", "Test Author");
        when(bookStorageService.existsBySource("gutenberg", "1234")).thenReturn(false);
        when(gutendexClient.getBook(1234)).thenReturn(Optional.of(gutendexBook));
        when(gutendexClient.fetchContent(anyString())).thenReturn("<html><body><p>Content</p></body></html>");

        ParsedChapter chapter = new ParsedChapter("Chapter 1", List.of("Paragraph"));
        when(contentParser.parse(anyString())).thenReturn(new ParsedBook(List.of(chapter)));

        Book savedBook = new Book("id", "Test Book", "Test Author", "", null, List.of(), false, false, false);
        when(bookStorageService.saveBook(any(BookEntity.class))).thenReturn(savedBook);

        bookImportService.importBook(1234);

        ArgumentCaptor<BookEntity> captor = ArgumentCaptor.forClass(BookEntity.class);
        verify(bookStorageService).saveBook(captor.capture());

        BookEntity saved = captor.getValue();
        assertEquals("gutenberg", saved.getSource());
        assertEquals("1234", saved.getSourceId());
    }

    private GutendexBook createGutendexBook(int id, String title, String author) {
        return new GutendexBook(
            id, title,
            List.of(new GutendexBook.Author(author, null, null)),
            List.of("Fiction"), List.of("Classics Bookshelf"), List.of("en"),
            Map.of("text/html", "http://gutenberg.org/files/" + id + "/" + id + "-h.htm"),
            1000
        );
    }
}
