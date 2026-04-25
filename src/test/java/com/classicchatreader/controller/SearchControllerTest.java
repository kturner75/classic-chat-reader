package com.classicchatreader.controller;

import org.apache.lucene.queryparser.classic.ParseException;
import com.classicchatreader.service.SearchService;
import com.classicchatreader.service.SearchService.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    @Test
    void search_returnsResults() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult("book", "moby-dick", null, null, "Moby Dick", null, 1.5f)
        );
        when(searchService.search("Moby", null, null, 10)).thenReturn(results);

        mockMvc.perform(get("/api/search").param("q", "Moby"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].type", is("book")))
            .andExpect(jsonPath("$[0].bookId", is("moby-dick")))
            .andExpect(jsonPath("$[0].title", is("Moby Dick")));
    }

    @Test
    void search_paragraphResult_includesIndexAndSnippet() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult("paragraph", "moby-dick", "ch1", 0, null, "Call me Ishmael...", 2.0f)
        );
        when(searchService.search("Ishmael", null, null, 10)).thenReturn(results);

        mockMvc.perform(get("/api/search").param("q", "Ishmael"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type", is("paragraph")))
            .andExpect(jsonPath("$[0].chapterId", is("ch1")))
            .andExpect(jsonPath("$[0].paragraphIndex", is(0)))
            .andExpect(jsonPath("$[0].snippet", is("Call me Ishmael...")));
    }

    @Test
    void search_noResults_returnsEmptyArray() throws Exception {
        when(searchService.search("nonexistent", null, null, 10)).thenReturn(List.of());

        mockMvc.perform(get("/api/search").param("q", "nonexistent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void search_withCustomLimit_usesLimit() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult("book", "book1", null, null, "Book One", null, 1.0f),
            new SearchResult("book", "book2", null, null, "Book Two", null, 0.9f)
        );
        when(searchService.search("Book", null, null, 2)).thenReturn(results);

        mockMvc.perform(get("/api/search").param("q", "Book").param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void search_withBookId_filtersResults() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult("paragraph", "moby-dick", "ch1", 0, null, "Call me Ishmael...", 2.0f)
        );
        when(searchService.search("Ishmael", "moby-dick", null, 10)).thenReturn(results);

        mockMvc.perform(get("/api/search").param("q", "Ishmael").param("bookId", "moby-dick"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].bookId", is("moby-dick")));
    }

    @Test
    void search_withChapterId_filtersResults() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult("paragraph", "moby-dick", "ch2", 4, null, "...white whale...", 1.8f)
        );
        when(searchService.search("whale", "moby-dick", "ch2", 10)).thenReturn(results);

        mockMvc.perform(get("/api/search")
                .param("q", "whale")
                .param("bookId", "moby-dick")
                .param("chapterId", "ch2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].chapterId", is("ch2")));
    }

    @Test
    void search_missingQuery_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/search"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void search_serviceThrowsIOException_returns500() throws Exception {
        when(searchService.search("error", null, null, 10)).thenThrow(new IOException("Index error"));

        mockMvc.perform(get("/api/search").param("q", "error"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void search_serviceThrowsParseException_returns500() throws Exception {
        when(searchService.search("bad:query", null, null, 10)).thenThrow(new ParseException("Invalid syntax"));

        mockMvc.perform(get("/api/search").param("q", "bad:query"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void search_multipleResultTypes_returnsMixed() throws Exception {
        List<SearchResult> results = List.of(
            new SearchResult("book", "moby-dick", null, null, "Moby Dick", null, 2.0f),
            new SearchResult("paragraph", "moby-dick", "ch1", 0, null, "...Moby Dick...", 1.5f)
        );
        when(searchService.search("Moby", null, null, 10)).thenReturn(results);

        mockMvc.perform(get("/api/search").param("q", "Moby"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].type", is("book")))
            .andExpect(jsonPath("$[1].type", is("paragraph")));
    }
}
