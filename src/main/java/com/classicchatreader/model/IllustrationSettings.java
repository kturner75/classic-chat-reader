package com.classicchatreader.model;

public record IllustrationSettings(
    String style,
    String promptPrefix,
    String setting,
    String reasoning,
    String coverSubject,
    String coverFocus
) {
    /** Match BookEntity column lengths so PUT cannot persist unbounded Imagine prefixes. */
    public static final int STYLE_MAX = 255;
    public static final int PREFIX_MAX = 1000;
    public static final int SETTING_MAX = 1000;
    public static final int REASONING_MAX = 2000;
    public static final int COVER_SUBJECT_MAX = 32;
    public static final int COVER_FOCUS_MAX = 4000;

    public static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max);
    }

    public static IllustrationSettings defaults() {
        return new IllustrationSettings(
            "vintage book illustration",
            "vintage book illustration style, detailed pen and ink with subtle watercolor tints,",
            null,
            "Default classic illustration style",
            null,
            null
        );
    }
}
