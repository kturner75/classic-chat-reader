package com.classicchatreader.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReadingBuddyPropertiesTest {

    @Test
    void defaults_matchDesignDocument() {
        ReadingBuddyProperties props = new ReadingBuddyProperties();

        assertFalse(props.isEnabled());
        assertEquals(8, props.getMinParagraphGap().getRare());
        assertEquals(4, props.getMinParagraphGap().getOccasional());
        assertEquals(2, props.getMinParagraphGap().getChatty());
        assertEquals(180_000L, props.getMinCooldownMs().getRare());
        assertEquals(90_000L, props.getMinCooldownMs().getOccasional());
        assertEquals(45_000L, props.getMinCooldownMs().getChatty());
        assertEquals(6, props.getMaxCommentsPerChapter());
        assertEquals(12, props.getMaxCommentsPerHour());
        assertEquals(60, props.getProactive().getMaxWords());
        assertEquals(150, props.getChat().getMaxWords());
        assertEquals(12, props.getChat().getMaxContextMessages());
        assertEquals(1500, props.getMemory().getSummaryMaxChars());
        assertEquals(20, props.getMemory().getRecentMessages());
        assertEquals(8, props.getMemory().getSummaryEveryMessages());
        assertEquals(45, props.getQuietDefaultMinutes());
        assertEquals(2000, props.getUserMessageMaxChars());
        assertEquals(4, props.getPostChatParagraphGap());
    }

    @Test
    void frequencyHelpers_mapKnownValuesAndFallbackToRare() {
        ReadingBuddyProperties props = new ReadingBuddyProperties();

        assertEquals(8, props.minParagraphGapFor("rare"));
        assertEquals(4, props.minParagraphGapFor("occasional"));
        assertEquals(2, props.minParagraphGapFor("chatty"));
        assertEquals(8, props.minParagraphGapFor("unknown"));
        assertEquals(8, props.minParagraphGapFor(null));

        assertEquals(180_000L, props.minCooldownMsFor("rare"));
        assertEquals(90_000L, props.minCooldownMsFor("OCCASIONAL"));
        assertEquals(45_000L, props.minCooldownMsFor("chatty"));
        assertEquals(180_000L, props.minCooldownMsFor("nope"));
    }
}
