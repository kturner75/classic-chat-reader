package com.classicchatreader.model;

import java.util.List;

public record IllustrationStyleSuggestions(
        String setting,
        List<IllustrationStyleSuggestion> suggestions
) {
    public static IllustrationStyleSuggestions empty() {
        return new IllustrationStyleSuggestions(null, List.of());
    }
}
