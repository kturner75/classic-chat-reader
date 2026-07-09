package com.classicchatreader.model;

import java.util.List;

/**
 * Immutable canned reading-buddy persona definition (code catalog, not DB).
 */
public record ReadingBuddyPersona(
        String id,
        String displayName,
        String shortBlurb,
        String systemPrompt,
        List<String> toneTags,
        String portraitPath,
        double temperature,
        int maxProactiveWords,
        int maxChatWords
) {
}
