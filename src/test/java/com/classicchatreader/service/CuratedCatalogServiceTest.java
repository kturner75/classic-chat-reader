package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuratedCatalogServiceTest {

    private final CuratedCatalogService curatedCatalogService = new CuratedCatalogService();

    @Test
    void searchFindsRecentlyAddedRecommendedTitles() {
        List<CuratedCatalogService.CuratedCatalogBook> douglassResults = curatedCatalogService.search("douglass");
        List<CuratedCatalogService.CuratedCatalogBook> gaskellResults = curatedCatalogService.search("north and south");
        List<CuratedCatalogService.CuratedCatalogBook> wildeResults = curatedCatalogService.search("earnest");

        assertTrue(douglassResults.stream().anyMatch(book -> book.gutenbergId() == 23));
        assertTrue(gaskellResults.stream().anyMatch(book -> book.gutenbergId() == 4276));
        assertTrue(wildeResults.stream().anyMatch(book -> book.gutenbergId() == 844));
    }

    @Test
    void searchFindsBl052ShortFictionAndDramaByAssignedWorkName() {
        assertTrue(curatedCatalogService.search("amontillado").stream()
                .anyMatch(book -> book.gutenbergId() == 1063));
        assertTrue(curatedCatalogService.search("jumping frog").stream()
                .anyMatch(book -> book.gutenbergId() == 3189));
        assertTrue(curatedCatalogService.search("rappaccini").stream()
                .anyMatch(book -> book.gutenbergId() == 512));
        assertTrue(curatedCatalogService.search("trifles").stream()
                .anyMatch(book -> book.gutenbergId() == 10623));
    }

    @Test
    void searchFindsBl052PoetryContainers() {
        assertTrue(curatedCatalogService.search("sonnet 18").stream()
                .anyMatch(book -> book.gutenbergId() == 1041));
        assertTrue(curatedCatalogService.search("ulysses").stream()
                .anyMatch(book -> book.gutenbergId() == 8601 && book.author().contains("Tennyson")));
        assertTrue(curatedCatalogService.search("my last duchess").stream()
                .anyMatch(book -> book.gutenbergId() == 16376));
        assertTrue(curatedCatalogService.search("because i could not stop for death").stream()
                .anyMatch(book -> book.gutenbergId() == 12242));
        assertTrue(curatedCatalogService.search("prufrock").stream()
                .anyMatch(book -> book.gutenbergId() == 1459));
    }

    @Test
    void curatedIdsIncludeVerifiedBl052ContainersAndSkipUnverifiedChopinSuggestion() {
        assertTrue(curatedCatalogService.isCuratedGutenbergId(1063));
        assertTrue(curatedCatalogService.isCuratedGutenbergId(3189));
        assertTrue(curatedCatalogService.isCuratedGutenbergId(512));
        assertTrue(curatedCatalogService.isCuratedGutenbergId(10623));
        // Suggested PG 160 is Chopin's Awakening collection and does not contain
        // "The Story of an Hour" (verified against PG catalog/text); do not curate it for BL-052.
        assertFalse(curatedCatalogService.isCuratedGutenbergId(160));
    }
}
