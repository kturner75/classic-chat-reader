package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.model.IllustrationSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookCoverPromptBuilderTest {

    @Test
    void placeCoverDoesNotForceAPortraitOrSilhouette() {
        BookEntity book = new BookEntity("The Fall of the House of Usher", "Edgar Allan Poe", "gutenberg");
        book.setDescription("A decaying mansion and the last of a doomed family.");
        IllustrationSettings style = new IllustrationSettings(
                "woodcut",
                "gothic woodcut, high contrast, stormy atmosphere,",
                "19th century American gothic, rural manor",
                "place-led cover",
                "place",
                "the decaying House of Usher, no people"
        );

        String prompt = BookCoverPromptBuilder.build(book, style);

        assertTrue(prompt.contains("House of Usher"));
        assertTrue(prompt.contains("gothic woodcut, high contrast, stormy atmosphere,"));
        assertTrue(prompt.contains("Cover subject class: place"));
        assertTrue(prompt.contains("the decaying House of Usher"));
        assertTrue(prompt.contains("Do not add a person"));
        assertFalse(prompt.contains("simple silhouette"));
        assertFalse(prompt.contains("One bold central character facing the viewer"));
        assertTrue(prompt.contains("No title text"));
    }

    @Test
    void characterCoverAsksForAVisibleFace() {
        BookEntity book = new BookEntity("The Scarlet Letter", "Nathaniel Hawthorne", "gutenberg");
        IllustrationSettings style = new IllustrationSettings(
                "oil-painting",
                "painterly literary oil,",
                "Puritan New England",
                "character-led",
                "character",
                "Hester Prynne with the scarlet A"
        );
        String prompt = BookCoverPromptBuilder.build(book, style);
        assertTrue(prompt.contains("visible face"));
        assertFalse(prompt.contains("Do not add a person"));
    }

    @Test
    void usesDefaultPrefixWhenStyleMissing() {
        BookEntity book = new BookEntity("The Scarlet Letter", "Nathaniel Hawthorne", "gutenberg");
        String prompt = BookCoverPromptBuilder.build(book, null);
        assertTrue(prompt.startsWith(BookCoverPromptBuilder.DEFAULT_PREFIX));
        assertTrue(prompt.contains("Do not default to a portrait"));
    }

    @Test
    void focusWithoutSubjectUsesGenericGuidance() {
        BookEntity book = new BookEntity("The Scarlet Letter", "Nathaniel Hawthorne", "gutenberg");
        IllustrationSettings style = new IllustrationSettings(
                "oil-painting",
                "painterly literary oil,",
                "Puritan New England",
                "partial analysis",
                null,
                "Hester Prynne with the scarlet A"
        );
        String prompt = BookCoverPromptBuilder.build(book, style);
        assertTrue(prompt.contains("Suggested focus: Hester Prynne with the scarlet A"));
        assertTrue(prompt.contains("Do not default to a portrait"));
        assertTrue(prompt.contains("If the subject is a person, show a visible face"));
        assertFalse(prompt.contains("Cover subject class:"));
    }
}
