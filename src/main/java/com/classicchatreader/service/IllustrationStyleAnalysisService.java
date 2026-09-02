package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.model.IllustrationStyleSuggestion;
import com.classicchatreader.model.IllustrationStyleSuggestions;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IllustrationStyleAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(IllustrationStyleAnalysisService.class);

    public static final int DEFAULT_SUGGESTION_LIMIT = 4;
    public static final int MIN_SUGGESTION_LIMIT = 1;
    public static final int MAX_SUGGESTION_LIMIT = 5;

    private static final String STYLE_MENU = """
            Available illustration styles (use these as a menu; you may invent a close variant when the book needs it):

            - woodcut: Bold black and white, stark contrasts, medieval aesthetic
              Best for: Gothic horror, medieval tales, dark folklore (Dracula, Canterbury Tales, Beowulf)

            - watercolor: Soft, flowing color, warm tints, children's-book warmth
              Best for: Children's and juvenile novels, gentle domestic stories, romantic and nature writing
              (Alcott, Burnett, Montgomery, Spyri, Austen, Wordsworth)

            - pen-and-ink: Detailed line work that MAY include watercolor tints and color. Do not force
              black-and-white. Best for: Victorian mysteries and adventures when a graphic plate is right
              (Conan Doyle, Stevenson). Not the default for children's novels.

            - oil-painting: Rich, dramatic, classical fine art feel
              Best for: Epic narratives, war stories, grand historical fiction (Tolstoy, Homer, Hugo)

            - art-nouveau: Flowing organic lines, decorative, elegant
              Best for: Fairy tales, fantasy, aesthetic movement works (Wilde, Morris, fairy tales)

            - expressionist: Bold colors, emotional distortion, psychological intensity
              Best for: Psychological drama, modernist works, existential themes (Dostoevsky, Kafka, Poe)

            COLOR RULE: Children's and juvenile books must use color (watercolor or tinted illustration).
            Never choose black-and-white woodcut or monochrome pen-and-ink for those books.
            When you pick watercolor or a tinted plate, promptPrefix must say color or watercolor
            and must not say "black and white".
            """;

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

            %s
            Also choose a coverSubject for a text-free book cover (the app overlays the real title):
            - character: only if one specific person is the book's symbol (e.g. Hester Prynne)
            - place: when a building, landscape, or city is the icon (e.g. the House of Usher)
            - object: a defining artifact (e.g. a white whale, a raven)
            - emblem: a simple symbolic image when no single character or place is the icon

            Put the cover choice in coverSubject and coverFocus only. promptPrefix must stay style-only
            (medium, palette, atmosphere) so chapter illustrations and portraits do not inherit a cover subject.

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
              "coverSubject": "character|place|object|emblem",
              "coverFocus": "One concrete cover subject, e.g. the decaying House of Usher, no people",
              "promptPrefix": "Style only, e.g. 'gothic woodcut, high contrast, stormy atmosphere,'",
              "setting": "The specific cultural, geographic, and historical setting (country, time period, religion/culture, architectural style)",
              "reasoning": "Brief explanation of why this style and cover subject fit the book"
            }
            """, title, author, truncateText(openingText, 1500), STYLE_MENU);

        try {
            String generatedText = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.5));
            String json = extractJson(generatedText);
            JsonNode settingsNode = objectMapper.readTree(json);

            return new IllustrationSettings(
                    settingsNode.get("style").asText("watercolor"),
                    settingsNode.has("promptPrefix") ? settingsNode.get("promptPrefix").asText()
                            : "warm watercolor, vintage children's book illustration, soft color,",
                    settingsNode.has("setting") ? settingsNode.get("setting").asText() : null,
                    settingsNode.has("reasoning") ? settingsNode.get("reasoning").asText() : "AI recommended",
                    normalizeCoverSubject(textOrNull(settingsNode, "coverSubject")),
                    blankToNull(textOrNull(settingsNode, "coverFocus"))
            );

        } catch (Exception e) {
            log.error("Failed to analyze book for illustration style", e);
            return IllustrationSettings.defaults();
        }
    }

    public IllustrationStyleSuggestions suggestStylesForBook(
            String title, String author, String openingText, int limit) {
        int n = clampSuggestionLimit(limit);
        if (cacheOnly) {
            log.info("Skipping illustration style suggestions in cache-only mode for '{}'", title);
            return IllustrationStyleSuggestions.empty();
        }
        String prompt = String.format("""
            Analyze this book and suggest up to %d DISTINCT illustration styles that fit its theme.

            Book Title: %s
            Author: %s
            Opening Text:
            ---
            %s
            ---

            %s
            These are operator choices for portraits, covers, and chapter plates. Rank the best fit first.
            Each suggestion must be a real alternative (different medium or palette), not a near-duplicate.
            Honor the COLOR RULE. promptPrefix is style-only (medium, palette, atmosphere) — no cover subject.

            Identify one shared setting for the book (country, time period, culture, architecture).

            Respond with ONLY valid JSON in this exact format, no other text:
            {
              "setting": "The specific cultural, geographic, and historical setting",
              "suggestions": [
                {
                  "style": "style_name",
                  "label": "Short chip label, 2-4 words",
                  "promptPrefix": "Imagine style prefix ending with a comma",
                  "reasoning": "One sentence why this style fits this book"
                }
              ]
            }
            """, n, title, author, truncateText(openingText, 1500), STYLE_MENU);

        try {
            String generatedText = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.6));
            return parseSuggestions(generatedText, n);
        } catch (Exception e) {
            log.error("Failed to suggest illustration styles for '{}'", title, e);
            return IllustrationStyleSuggestions.empty();
        }
    }

    static int clampSuggestionLimit(int limit) {
        if (limit < MIN_SUGGESTION_LIMIT) {
            return DEFAULT_SUGGESTION_LIMIT;
        }
        return Math.min(MAX_SUGGESTION_LIMIT, limit);
    }

    static IllustrationStyleSuggestions parseSuggestions(String generatedText, int limit) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(extractJson(generatedText));
        String setting = blankToNull(textOrNull(root, "setting"));
        JsonNode arr = root.get("suggestions");
        List<IllustrationStyleSuggestion> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode node : arr) {
                if (out.size() >= limit) {
                    break;
                }
                String style = node != null && node.has("style") ? node.get("style").asText("") : "";
                if (style == null || style.isBlank()) {
                    continue;
                }
                String label = node.has("label") ? node.get("label").asText(style) : style;
                out.add(new IllustrationStyleSuggestion(
                        style.trim(),
                        label == null || label.isBlank() ? style.trim() : label.trim(),
                        node.has("promptPrefix") ? node.get("promptPrefix").asText("") : "",
                        node.has("reasoning") ? node.get("reasoning").asText("") : ""
                ));
            }
        }
        return new IllustrationStyleSuggestions(setting, List.copyOf(out));
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    static String normalizeCoverSubject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "character", "place", "object", "emblem" -> value;
            default -> null;
        };
    }

    static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        throw new IllegalArgumentException("No JSON found in response: " + text);
    }
}
