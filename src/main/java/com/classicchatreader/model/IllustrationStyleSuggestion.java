package com.classicchatreader.model;

public record IllustrationStyleSuggestion(
        String style,
        String label,
        String promptPrefix,
        String reasoning
) {
}
