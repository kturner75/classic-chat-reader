package com.classicchatreader.model;

import java.util.List;

public record ChapterRecapPayload(
        String shortSummary,
        List<String> keyEvents,
        List<CharacterDelta> characterDeltas
) {
    public record CharacterDelta(String characterName, String delta) {}
}
