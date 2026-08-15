package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.model.IllustrationSettings;

/**
 * Builds the default book-cover image prompt. Style/setting come from book analysis;
 * subject is chosen per book (character, place, object, or emblem) instead of a
 * one-size silhouette or portrait rule.
 */
final class BookCoverPromptBuilder {

    static final String DEFAULT_PREFIX = "classic literary illustration,";

    private BookCoverPromptBuilder() {
    }

    static String build(BookEntity book, IllustrationSettings style) {
        String setting = style == null || style.setting() == null ? "" : style.setting();
        String prefix = style == null || style.promptPrefix() == null || style.promptPrefix().isBlank()
                ? DEFAULT_PREFIX
                : style.promptPrefix();
        String description = book.getDescription() == null ? "" : book.getDescription();
        if (description.length() > 500) {
            description = description.substring(0, 500);
        }
        return String.format(
                "%s text-free illustrated book cover artwork for %s by %s. "
                        + "Choose one iconic focal subject that best represents this book: "
                        + "a specific character only if that person is the book's symbol; "
                        + "otherwise a place, object, or emblem. Do not default to a portrait. "
                        + "If the subject is a person, show a visible face with a clear expression "
                        + "— not a silhouette, not back-turned, not a featureless shadow. "
                        + "If the subject is a place, object, or emblem, do not add a person. "
                        + "Match the recommended art style and atmosphere. "
                        + "Strong contrast, rich color, readable at small thumbnail size. "
                        + "No title text, no author text, no words, no letters, no typography, no logos. "
                        + "Avoid tiny decorative borders, printed paper texture, dense background detail, "
                        + "and generic unrelated portraits. Setting: %s. Themes: %s",
                prefix,
                book.getTitle(),
                book.getAuthor(),
                setting,
                description
        );
    }
}
