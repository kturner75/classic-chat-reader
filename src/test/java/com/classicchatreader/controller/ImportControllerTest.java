package com.classicchatreader.controller;

import com.classicchatreader.service.BookImportService;
import com.classicchatreader.service.BookImportService.CatalogModeStatus;
import com.classicchatreader.service.BookImportService.ImportResult;
import com.classicchatreader.service.BookImportService.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImportController.class)
class ImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookImportService bookImportService;

    @Test
    void searchGutenbergReturnsResults() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult(1234, "Pride and Prejudice", "Jane Austen", 50000, List.of("Courtship fiction"), List.of("Best Books Ever Listings"), false),
            new SearchResult(5678, "Sense and Sensibility", "Jane Austen", 30000, List.of("England -- Fiction"), List.of("Romance"), true)
        );
        when(bookImportService.searchGutenberg("austen")).thenReturn(results);

        mockMvc.perform(get("/api/import/search").param("q", "austen"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].gutenbergId").value(1234))
            .andExpect(jsonPath("$[0].title").value("Pride and Prejudice"))
            .andExpect(jsonPath("$[0].author").value("Jane Austen"))
            .andExpect(jsonPath("$[0].downloadCount").value(50000))
            .andExpect(jsonPath("$[0].subjects[0]").value("Courtship fiction"))
            .andExpect(jsonPath("$[0].bookshelves[0]").value("Best Books Ever Listings"))
            .andExpect(jsonPath("$[0].alreadyImported").value(false))
            .andExpect(jsonPath("$[1].gutenbergId").value(5678))
            .andExpect(jsonPath("$[1].alreadyImported").value(true));
    }

    @Test
    void searchGutenbergReturnsEmptyListForNoResults() throws Exception {
        when(bookImportService.searchGutenberg("nonexistent")).thenReturn(List.of());

        mockMvc.perform(get("/api/import/search").param("q", "nonexistent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getPopularBooksReturnsResults() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult(1, "Frankenstein", "Mary Shelley", 100000, List.of("Science fiction"), List.of("Horror"), false)
        );
        when(bookImportService.getPopularBooks(1)).thenReturn(results);

        mockMvc.perform(get("/api/import/popular"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].gutenbergId").value(1))
            .andExpect(jsonPath("$[0].title").value("Frankenstein"));
    }

    @Test
    void getPopularBooksAcceptsPageParameter() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult(2, "Dracula", "Bram Stoker", 80000, List.of("Vampires -- Fiction"), List.of("Gothic"), false)
        );
        when(bookImportService.getPopularBooks(3)).thenReturn(results);

        mockMvc.perform(get("/api/import/popular").param("page", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Dracula"));
    }

    @Test
    void getCatalogModeReturnsCurrentMode() throws Exception {
        when(bookImportService.getCatalogModeStatus()).thenReturn(new CatalogModeStatus("curated", true, 20));

        mockMvc.perform(get("/api/import/catalog-mode"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalogMode").value("curated"))
            .andExpect(jsonPath("$.curatedOnly").value(true))
            .andExpect(jsonPath("$.curatedBookCount").value(20));
    }

    @Test
    void importBookReturnsOkOnSuccess() throws Exception {
        ImportResult result = new ImportResult(true, "book-123", "Successfully imported", 10, 500);
        when(bookImportService.importBook(1234)).thenReturn(result);

        mockMvc.perform(post("/api/import/gutenberg/1234"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.bookId").value("book-123"))
            .andExpect(jsonPath("$.message").value("Successfully imported"))
            .andExpect(jsonPath("$.chapterCount").value(10))
            .andExpect(jsonPath("$.paragraphCount").value(500));
    }

    @Test
    void importBookReturnsConflictIfAlreadyImported() throws Exception {
        ImportResult result = new ImportResult(false, "existing-id", "Book already imported", 0, 0);
        when(bookImportService.importBook(1234)).thenReturn(result);

        mockMvc.perform(post("/api/import/gutenberg/1234"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.bookId").value("existing-id"))
            .andExpect(jsonPath("$.message").value("Book already imported"));
    }

    @Test
    void importBookReturnsBadRequestOnFailure() throws Exception {
        ImportResult result = new ImportResult(false, null, "Book not found in Gutenberg", 0, 0);
        when(bookImportService.importBook(9999)).thenReturn(result);

        mockMvc.perform(post("/api/import/gutenberg/9999"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.bookId").doesNotExist())
            .andExpect(jsonPath("$.message").value("Book not found in Gutenberg"));
    }

    @Test
    void importBookReturnsBadRequestIfNoHtmlAvailable() throws Exception {
        ImportResult result = new ImportResult(false, null, "No HTML version available", 0, 0);
        when(bookImportService.importBook(5555)).thenReturn(result);

        mockMvc.perform(post("/api/import/gutenberg/5555"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("No HTML version available"));
    }

    @Test
    void importBookReturnsBadRequestIfContentFetchFails() throws Exception {
        ImportResult result = new ImportResult(false, null, "Failed to fetch content: Network error", 0, 0);
        when(bookImportService.importBook(7777)).thenReturn(result);

        mockMvc.perform(post("/api/import/gutenberg/7777"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Failed to fetch content: Network error"));
    }

    @Test
    void importBookReturnsBadRequestIfNoContentParsed() throws Exception {
        ImportResult result = new ImportResult(false, null, "No content could be parsed", 0, 0);
        when(bookImportService.importBook(8888)).thenReturn(result);

        mockMvc.perform(post("/api/import/gutenberg/8888"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("No content could be parsed"));
    }
}
