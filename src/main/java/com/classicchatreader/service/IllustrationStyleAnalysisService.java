package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IllustrationStyleAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(IllustrationStyleAnalysisService.class);

    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    public IllustrationStyleAnalysisService(@Qualifier("reasoningLlmProvider") LlmProvider reasoningProvider) {
        this.reasoningProvider = reasoningProvider;
        log.info("Illustration style analysis service initialized with provider: {}", reasoningProvider.getProviderName());
    }

    public boolean isReasoningProviderAvailable() {
        return !cacheOnly && reasoningProvider.isAvailable();
    }

    /**
     * @deprecated Use {@link #isReasoningProviderAvailable()} instead
     */
    @Deprecated
    public boolean isOllamaAvailable() {
        return isReasoningProviderAvailable();
    }

    public IllustrationSettings analyzeBookForStyle(String title, String author, String openingText) {
        if (cacheOnly) {
            log.info("Skipping illustration style analysis in cache-only mode for '{}'", title);
            return IllustrationSettings.defaults();
        }
        String prompt = String.format("""
            Analyze this book and recommend the best illustration style for generating AI art that accompanies the reading experience.

            Book Title: %s
            Author: %s
            Opening Text:
            ---
            %s
            ---

            Available illustration styles (choose the one that best matches the book's character):

            - woodcut: Bold black and white, stark contrasts, medieval aesthetic
              Best for: Gothic horror, medieval tales, dark folklore (Dracula, Canterbury Tales, Beowulf)

            - watercolor: Soft, flowing colors, dreamy and romantic
              Best for: Romantic literature, nature writing, gentle narratives (Austen, Thoreau, Wordsworth)

            - pen-and-ink: Detailed line work, crosshatching, Victorian sensibility
              Best for: Victorian fiction, mysteries, adventures (Dickens, Conan Doyle, Stevenson)

            - oil-painting: Rich, dramatic, classical fine art feel
              Best for: Epic narratives, war stories, grand historical fiction (Tolstoy, Homer, Hugo)

            - art-nouveau: Flowing organic lines, decorative, elegant
              Best for: Fairy tales, fantasy, aesthetic movement works (Wilde, Morris, fairy tales)

            - expressionist: Bold colors, emotional distortion, psychological intensity
              Best for: Psychological drama, modernist works, existential themes (Dostoevsky, Kafka, Poe)

            Consider the book's:
            - Genre and emotional tone
            - Time period and setting
            - Narrative style and atmosphere
            - Visual imagery in the text

            IMPORTANT: You must also identify the book's cultural and geographic setting. This is CRITICAL for accurate illustrations.
            Examples:
            - "19th century Russia, Russian Orthodox Christian culture, Slavic architecture"
            - "Victorian England, English countryside and London, Anglican/Protestant culture"
            - "Ancient Greece, Mediterranean, Greek mythology and temples"
            - "1920s American South, rural Georgia, African American community"

            Respond with ONLY valid JSON in this exact format, no other text:
            {
              "style": "style_name",
              "promptPrefix": "A detailed prompt prefix describing the visual style, e.g., 'vintage watercolor illustration, soft pastels, romantic atmosphere,'",
              "setting": "The specific cultural, geographic, and historical setting (country, time period, religion/culture, architectural style)",
              "reasoning": "Brief explanation of why this style fits the book"
            }
            """, title, author, truncateText(openingText, 1500));

        try {
            String generatedText = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.5));

            // Extract JSON from response
            String json = extractJson(generatedText);
            JsonNode settingsNode = objectMapper.readTree(json);

            return new IllustrationSettings(
                    settingsNode.get("style").asText("pen-and-ink"),
                    settingsNode.has("promptPrefix") ? settingsNode.get("promptPrefix").asText() : "detailed book illustration,",
                    settingsNode.has("setting") ? settingsNode.get("setting").asText() : null,
                    settingsNode.has("reasoning") ? settingsNode.get("reasoning").asText() : "AI recommended"
            );

        } catch (Exception e) {
            log.error("Failed to analyze book for illustration style", e);
            return IllustrationSettings.defaults();
        }
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        throw new IllegalArgumentException("No JSON found in response: " + text);
    }
}
