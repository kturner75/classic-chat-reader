package com.classicchatreader.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Documents the reasoning-client wall-clock default. grok-4.6 high + chapter-map
 * prefetch can exceed 3 minutes; studio discovery polls for ~6 minutes.
 */
class LlmProviderConfigTimeoutTest {

    @Test
    void reasoningTimeoutDefaultIs420SecondsAndChatStays60() throws Exception {
        Properties props = loadApplicationProperties();

        assertEquals("420", props.getProperty("ai.reasoning.timeout-seconds"));
        assertEquals("60", props.getProperty("ai.chat.timeout-seconds"));
        assertEquals("${ai.reasoning.timeout-seconds:420}",
                props.getProperty("recap.reasoning.timeout-seconds"));
        assertEquals("${ai.reasoning.timeout-seconds:420}",
                props.getProperty("quiz.reasoning.timeout-seconds"));
    }

    @Test
    void llmProviderConfigFallbacksMatchReasoningDefault() throws Exception {
        assertEquals("${ai.reasoning.timeout-seconds:420}", valueAnnotation("reasoningTimeoutSeconds"));
        assertEquals("${recap.reasoning.timeout-seconds:${ai.reasoning.timeout-seconds:420}}",
                valueAnnotation("recapReasoningTimeoutSeconds"));
        assertEquals("${quiz.reasoning.timeout-seconds:${ai.reasoning.timeout-seconds:420}}",
                valueAnnotation("quizReasoningTimeoutSeconds"));
        assertEquals("${ai.chat.timeout-seconds:60}", valueAnnotation("chatTimeoutSeconds"));
    }

    private static Properties loadApplicationProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = LlmProviderConfigTimeoutTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(in, "classpath application.properties");
            props.load(in);
        }
        return props;
    }

    private static String valueAnnotation(String fieldName) throws Exception {
        Field field = LlmProviderConfig.class.getDeclaredField(fieldName);
        return field.getAnnotation(Value.class).value();
    }
}
