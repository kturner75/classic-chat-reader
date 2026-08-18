package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    void searchFindsNewlyCuratedPopularTitles() {
        assertTrue(curatedCatalogService.search("romeo").stream()
                .anyMatch(book -> book.gutenbergId() == 1513));
        assertTrue(curatedCatalogService.search("gatsby").stream()
                .anyMatch(book -> book.gutenbergId() == 64317));
        assertTrue(curatedCatalogService.search("room with a view").stream()
                .anyMatch(book -> book.gutenbergId() == 2641));
        assertTrue(curatedCatalogService.search("beowulf").stream()
                .anyMatch(book -> book.gutenbergId() == 16328));
        assertTrue(curatedCatalogService.search("blue castle").stream()
                .anyMatch(book -> book.gutenbergId() == 67979));
        assertTrue(curatedCatalogService.search("gawain").stream()
                .anyMatch(book -> book.gutenbergId() == 66084 && "Anonymous".equals(book.author())));
    }

    @Test
    void searchFindsAnthologyVolumesByContainedShortWorkAlias() {
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
    void searchFindsPoetryVolumesByContainedPoemAlias() {
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
    void curatedCatalogIncludesVerifiedPartnerShortWorkContainers() {
        Set<Integer> curatedIds = curatedCatalogService.getPopularBooks().stream()
                .map(CuratedCatalogService.CuratedCatalogBook::gutenbergId)
                .collect(Collectors.toSet());

        Set<Integer> expectedPartnerContainers = Set.of(
                1063,   // The Cask of Amontillado
                3189,   // Sketches New and Old (Jumping Frog)
                512,    // Mosses from an Old Manse (Rappaccini's Daughter)
                10623,  // Plays (Trifles)
                1041,   // Shakespeare's Sonnets
                8601,   // Early Poems (Ulysses)
                16376,  // Browning's Shorter Poems (My Last Duchess)
                12242,  // Dickinson poems
                1459    // Prufrock and Other Observations
        );
        assertTrue(curatedIds.containsAll(expectedPartnerContainers));
        assertTrue(curatedCatalogService.getPopularBooks().stream()
                .filter(book -> book.gutenbergId() == 3189)
                .anyMatch(book -> book.aliases().stream()
                        .anyMatch(alias -> alias.toLowerCase().contains("jumping frog"))));
    }

    @Test
    void isCuratedGutenbergSource_matchesCatalogMembershipNotStoredFlags() {
        assertTrue(curatedCatalogService.isCuratedGutenbergSource("gutenberg", "1513"));
        assertTrue(curatedCatalogService.isCuratedGutenbergSource("Gutenberg", "1342"));
        assertFalse(curatedCatalogService.isCuratedGutenbergSource("gutenberg", "999999"));
        assertFalse(curatedCatalogService.isCuratedGutenbergSource("manual", "1513"));
        assertFalse(curatedCatalogService.isCuratedGutenbergSource("gutenberg", "not-a-number"));
        assertFalse(curatedCatalogService.isCuratedGutenbergSource(null, "1513"));
    }

    @Test
    void curatedCatalogStaysInNonIncreasingDownloadOrder() {
        List<CuratedCatalogService.CuratedCatalogBook> books = curatedCatalogService.getPopularBooks();
        for (int i = 1; i < books.size(); i++) {
            CuratedCatalogService.CuratedCatalogBook previous = books.get(i - 1);
            CuratedCatalogService.CuratedCatalogBook current = books.get(i);
            assertTrue(
                    previous.downloadCount() >= current.downloadCount(),
                    "catalog order broke between " + previous.title() + " and " + current.title());
        }
    }
}
