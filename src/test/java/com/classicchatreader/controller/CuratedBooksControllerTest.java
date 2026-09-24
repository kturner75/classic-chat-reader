package com.classicchatreader.controller;

import com.classicchatreader.service.CuratedBookStore;
import com.classicchatreader.service.CuratedCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Drives the controller over the real service and the Flyway-migrated schema. */
@DataJpaTest
@Import(CuratedBookStore.class)
class CuratedBooksControllerTest {

    @Autowired
    private CuratedBookStore store;
    private CuratedCatalogService catalog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        catalog = new CuratedCatalogService(store);
        mvc = MockMvcBuilders.standaloneSetup(new CuratedBooksController(catalog)).build();
    }

    @Test
    void listsAddsUnlistsAndRelists() throws Exception {
        mvc.perform(get("/api/curated-books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(91)))
                .andExpect(jsonPath("$[0].sourceId", is("1342")))
                .andExpect(jsonPath("$[0].status", is("active")));

        mvc.perform(post("/api/curated-books").contentType("application/json").content("""
                        {"source": "gutenberg", "sourceId": "2814", "title": "Dubliners", "author": "James Joyce",
                         "popularity": 23500, "bookshelves": ["Short Stories"], "aliases": ["Araby"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("Dubliners")))
                .andExpect(jsonPath("$.status", is("active")));
        assertTrue(catalog.isCuratedGutenbergId(2814));

        mvc.perform(patch("/api/curated-books/gutenberg/2814").contentType("application/json").content("{\"status\": \"inactive\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("inactive")));
        assertFalse(catalog.isCuratedGutenbergId(2814));

        mvc.perform(post("/api/curated-books").contentType("application/json").content("{\"source\": \"gutenberg\", \"sourceId\": \"2814\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliases[0]", is("Araby")))
                .andExpect(jsonPath("$.status", is("active")));
        assertTrue(catalog.isCuratedGutenbergId(2814));
    }

    @Test
    void rejectsBadInputAndUnknownBooks() throws Exception {
        mvc.perform(patch("/api/curated-books/gutenberg/999999").contentType("application/json").content("{\"status\": \"inactive\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/curated-books/gutenberg/1342").contentType("application/json").content("{\"status\": \"gone\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/curated-books/standardebooks/1342").contentType("application/json").content("{\"status\": \"inactive\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/curated-books").contentType("application/json").content("{\"source\": \"gutenberg\", \"sourceId\": \"abc\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/curated-books").contentType("application/json").content("{\"source\": \"gutenberg\", \"sourceId\": \"2814\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(catalog.isCuratedGutenbergId(1342));
    }

    @Test
    void remoteOrForwardedRequestsCannotReachTheCatalog() throws Exception {
        CuratedCatalogService untouched = mock(CuratedCatalogService.class);
        MockMvc remote = MockMvcBuilders.standaloneSetup(new CuratedBooksController(untouched)).build();
        remote.perform(get("/api/curated-books").with(r -> { r.setRemoteAddr("192.0.2.1"); return r; })).andExpect(status().isForbidden());
        remote.perform(get("/api/curated-books").header("X-Forwarded-For", "192.0.2.1")).andExpect(status().isForbidden());
        remote.perform(patch("/api/curated-books/gutenberg/1342").header("Origin", "https://other.example")
                .contentType("application/json").content("{\"status\": \"inactive\"}")).andExpect(status().isForbidden());
        remote.perform(post("http://other.example/api/curated-books").contentType("application/json")
                .content("{\"source\": \"gutenberg\", \"sourceId\": \"1342\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(untouched);
    }
}
