package com.classicchatreader.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises Spring relaxed binding of nested {@code reading-buddy.*} keys into
 * {@link ReadingBuddyProperties} (not just Java field defaults).
 */
@SpringBootTest(classes = ReadingBuddyPropertiesBindingTest.TestConfig.class)
@TestPropertySource(properties = {
        "reading-buddy.enabled=true",
        "reading-buddy.min-paragraph-gap.rare=99",
        "reading-buddy.min-cooldown-ms.chatty=12345",
        "reading-buddy.proactive.max-words=42",
        "reading-buddy.chat.max-context-messages=7",
        "reading-buddy.memory.summary-max-chars=999",
        "reading-buddy.memory.summary-every-messages=3",
        "reading-buddy.memory.max-retained-messages=50",
        "reading-buddy.quiet-default-minutes=30",
        "reading-buddy.post-chat-paragraph-gap=11"
})
class ReadingBuddyPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(ReadingBuddyProperties.class)
    static class TestConfig {
    }

    @Autowired
    private ReadingBuddyProperties properties;

    @Test
    void bindsNestedRelaxedPropertyNames() {
        assertTrue(properties.isEnabled());
        assertEquals(99, properties.getMinParagraphGap().getRare());
        assertEquals(12345L, properties.getMinCooldownMs().getChatty());
        assertEquals(42, properties.getProactive().getMaxWords());
        assertEquals(7, properties.getChat().getMaxContextMessages());
        assertEquals(999, properties.getMemory().getSummaryMaxChars());
        assertEquals(3, properties.getMemory().getSummaryEveryMessages());
        assertEquals(50, properties.getMemory().getMaxRetainedMessages());
        assertEquals(30, properties.getQuietDefaultMinutes());
        assertEquals(11, properties.getPostChatParagraphGap());
    }
}
