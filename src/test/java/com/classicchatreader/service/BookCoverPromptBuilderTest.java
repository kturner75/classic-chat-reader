package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.model.IllustrationSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookCoverPromptBuilderTest {

    @Test
    void doesNotForceAPortraitOrSilhouette() {
        BookEntity book = new BookEntity("The Fall of the House of Usher", "Edgar Allan Poe", "gutenberg");
        book.setDescription("A decaying mansion and the last of a doomed family.");
        IllustrationSettings style = new IllustrationSettings(
                "woodcut",
                "gothic woodcut, decaying manor as the sole focal subject,",
                "19th century American gothic, rural manor",
                "place-led cover"
        );

        String prompt = BookCoverPromptBuilder.build(book, style);

        assertTrue(prompt.contains("House of Usher"));
        assertTrue(prompt.contains("decaying manor as the sole focal subject"));
        assertTrue(prompt.contains("Choose one iconic focal subject"));
        assertTrue(prompt.contains("Do not default to a portrait"));
        assertTrue(prompt.contains("If the subject is a person, show a visible face"));
        assertTrue(prompt.contains("If the subject is a place, object, or emblem, do not add a person"));
        assertFalse(prompt.contains("simple silhouette"));
        assertFalse(prompt.contains("One bold central character facing the viewer"));
        assertTrue(prompt.contains("No title text"));
    }

    @Test
    void usesDefaultPrefixWhenStyleMissing() {
        BookEntity book = new BookEntity("The Scarlet Letter", "Nathaniel Hawthorne", "gutenberg");
        String prompt = BookCoverPromptBuilder.build(book, null);
        assertTrue(prompt.startsWith(BookCoverPromptBuilder.DEFAULT_PREFIX));
    }
}
