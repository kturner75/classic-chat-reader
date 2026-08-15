package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.model.IllustrationSettings;

/**
 * Builds the default book-cover image prompt. Style/setting stay shared with
 * chapter art; cover subject/focus are cover-only.
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
        return prefix
                + " text-free illustrated book cover artwork for "
                + book.getTitle()
                + " by "
                + book.getAuthor()
                + ". "
                + coverSubjectGuidance(style)
                + "Match the recommended art style and atmosphere. "
                + "Strong contrast, rich color, readable at small thumbnail size. "
                + "No title text, no author text, no words, no letters, no typography, no logos. "
                + "Avoid tiny decorative borders, printed paper texture, dense background detail, "
                + "and generic unrelated portraits. Setting: "
                + setting
                + ". Themes: "
                + description;
    }

    static String coverSubjectGuidance(IllustrationSettings style) {
        String subject = style == null ? null : style.coverSubject();
        String focus = style == null ? null : style.coverFocus();
        if (subject == null && (focus == null || focus.isBlank())) {
            return "Choose one iconic focal subject that best represents this book: "
                    + "a specific character only if that person is the book's symbol; "
                    + "otherwise a place, object, or emblem. Do not default to a portrait. "
                    + "If the subject is a person, show a visible face with a clear expression "
                    + "— not a silhouette, not back-turned, not a featureless shadow. "
                    + "If the subject is a place, object, or emblem, do not add a person. ";
        }
        StringBuilder guidance = new StringBuilder();
        if (subject != null) {
            guidance.append("Cover subject class: ").append(subject).append(". ");
        }
        if (focus != null && !focus.isBlank()) {
            guidance.append("Cover focus: ").append(focus.trim()).append(". ");
        }
        if ("character".equals(subject)) {
            guidance.append("Show a visible face with a clear expression — not a silhouette, ")
                    .append("not back-turned, not a featureless shadow. ");
        } else {
            guidance.append("Do not add a person. ");
        }
        return guidance.toString();
    }
}
