package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
