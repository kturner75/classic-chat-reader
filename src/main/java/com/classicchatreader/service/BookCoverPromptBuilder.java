package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.model.IllustrationSettings;

/**
 * Builds the default book-cover image prompt. Style/setting stay shared with
 * chapter art; cover subject/focus are cover-only.
 */
final class BookCoverPromptBuilder {

    static final String DEFAULT_PREFIX = "classic literary illustration,";
    static final int MAX_PROMPT_LENGTH = 2000;
    static final int MAX_COVER_FOCUS_LENGTH = 500;
    static final int MAX_PREFIX_IN_PROMPT = 280;
    static final int MAX_FOCUS_IN_PROMPT = 180;
    static final int MAX_SETTING_IN_PROMPT = 200;
    static final int MAX_THEMES_IN_PROMPT = 200;

    private BookCoverPromptBuilder() {
    }

    static String build(BookEntity book, IllustrationSettings style) {
        String prefix = clip(
                style == null || style.promptPrefix() == null || style.promptPrefix().isBlank()
                        ? DEFAULT_PREFIX
                        : style.promptPrefix(),
                MAX_PREFIX_IN_PROMPT);
        String setting = clip(
                style == null || style.setting() == null ? "" : style.setting(),
                MAX_SETTING_IN_PROMPT);
        String description = clip(
                book.getDescription() == null ? "" : book.getDescription(),
                MAX_THEMES_IN_PROMPT);
        String prompt = prefix
                + " text-free illustrated book cover artwork for "
                + book.getTitle()
                + " by "
                + book.getAuthor()
                + ". "
                + coverSubjectGuidance(style)
                + "Match the recommended art style and atmosphere. "
                + "Strong contrast, rich color, readable at small thumbnail size. "
                + "No title text, no author names, no unrequested words, typography, or logos. "
                + "A letter or emblem that is the chosen cover focus may appear as painted imagery, not typeset title. "
                + "Avoid tiny decorative borders, printed paper texture, dense background detail, "
                + "and generic unrelated portraits. "
                + "Setting: "
                + setting
                + ". Themes: "
                + description;
        if (prompt.length() <= MAX_PROMPT_LENGTH) {
            return prompt;
        }
        // Last resort: shrink prefix only; never drop the fixed rules or tail.
        int overflow = prompt.length() - MAX_PROMPT_LENGTH;
        String shorterPrefix = clip(prefix, Math.max(0, prefix.length() - overflow));
        return shorterPrefix + prompt.substring(prefix.length());
    }

    static String coverSubjectGuidance(IllustrationSettings style) {
        String subject = style == null ? null : style.coverSubject();
        String focus = style == null ? null : style.coverFocus();
        String clippedFocus = focus == null || focus.isBlank()
                ? null
                : clip(focus.trim(), MAX_FOCUS_IN_PROMPT);
        if (subject == null) {
            String hint = clippedFocus == null ? "" : "Suggested focus: " + clippedFocus + ". ";
            return hint
                    + "Choose one iconic focal subject that best represents this book: "
                    + "a specific character only if that person is the book's symbol; "
                    + "otherwise a place, object, or emblem. Do not default to a portrait. "
                    + "If the subject is a person, show a visible face with a clear expression "
                    + "— not a silhouette, not back-turned, not a featureless shadow. "
                    + "If the subject is a place, object, or emblem, do not add a person. ";
        }
        StringBuilder guidance = new StringBuilder();
        guidance.append("Cover subject class: ").append(subject).append(". ");
        if (clippedFocus != null) {
            guidance.append("Cover focus: ").append(clippedFocus).append(". ");
        }
        if ("character".equals(subject)) {
            guidance.append("Show a visible face with a clear expression — not a silhouette, ")
                    .append("not back-turned, not a featureless shadow. ");
        } else {
            guidance.append("Do not add a person. ");
        }
        return guidance.toString();
    }

    static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
