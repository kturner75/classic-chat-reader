package com.classicchatreader.service;

import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class IllustrationPromptService {

    private static final Logger log = LoggerFactory.getLogger(IllustrationPromptService.class);

    private static final Pattern OLD_FACE_BAN = Pattern.compile("DO NOT include human faces", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_SILHOUETTE_GUIDE = Pattern.compile(
            "silhouettes?, back views", Pattern.CASE_INSENSITIVE);
    private static final Pattern SILHOUETTE_WORD = Pattern.compile("\\bsilhouette", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_SILHOUETTE = Pattern.compile(
            "\\bnot (?:a )?silhouettes?\\b", Pattern.CASE_INSENSITIVE);

    static final String NARRATIVE_PLATE_GUARD =
            " Full-page narrative book plate, not a character portrait, not a head-and-shoulders"
                    + " or window-profile crop. Setting must occupy most of the frame;"
                    + " people at three-quarter or full figure in the scene.";

    static final String MEDIUM_LOCK =
            " same medium as the rest of this book, not a cartoon, not a children's watercolor.";

    private final LlmProvider reasoningProvider;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    public IllustrationPromptService(@Qualifier("reasoningLlmProvider") LlmProvider reasoningProvider) {
        this.reasoningProvider = reasoningProvider;
        log.info("Illustration prompt service initialized with provider: {}", reasoningProvider.getProviderName());
    }

    /**
     * Generate an image prompt for a chapter based on its content.
     *
     * @param bookTitle The book title
     * @param author The author
     * @param chapterTitle The chapter title
     * @param chapterContent The chapter text (will be truncated)
     * @param styleSettings The illustration style settings for this book
     * @return A prompt suitable for image generation
     */
    public String generatePromptForChapter(
            String bookTitle,
            String author,
            String chapterTitle,
            String chapterContent,
            IllustrationSettings styleSettings) {
        return generatePromptForChapter(bookTitle, author, chapterTitle, chapterContent, styleSettings, List.of());
    }

    public String generatePromptForChapter(
            String bookTitle,
            String author,
            String chapterTitle,
            String chapterContent,
            IllustrationSettings styleSettings,
            List<String> featuredCharacters) {
        List<String> cast = featuredCharacters == null ? List.of() : featuredCharacters.stream()
                .filter(n -> n != null && !n.isBlank())
                .toList();
        if (cacheOnly) {
            return fallbackPrompt(bookTitle, author, chapterTitle, styleSettings, cast);
        }

        String settingContext = styleSettings.setting() != null
                ? "Cultural/Geographic Setting: " + styleSettings.setting()
                : "";
        String castBlock = cast.isEmpty()
                ? "CAST: no named portrait characters for this plate. Do not invent named people."
                : "CAST (include each of these people by name, with visible faces; do not add other named characters):\n- "
                        + String.join("\n- ", cast);

        String prompt = String.format("""
            You are creating a prompt for an AI image generator to illustrate a chapter from a classic book.

            Book: %s by %s
            Chapter: %s
            Illustration Style: %s
            %s
            %s

            Chapter content excerpt:
            ---
            %s
            ---

            Generate a single, detailed image prompt that captures the essence of this chapter.

            CRITICAL REQUIREMENTS FOR CULTURAL ACCURACY:
            - The illustration MUST accurately reflect the book's specific cultural and geographic setting
            - Architecture, clothing, religious symbols, and landscapes must match the setting exactly
            - For Russian literature: use Russian Orthodox churches (onion domes), Slavic architecture, Russian landscapes
            - For English literature: use appropriate English/British architecture, countryside, weather
            - For American literature: use regionally-appropriate American settings
            - NEVER mix cultural elements (e.g., no Buddhist temples in Russian novels, no pagodas in English countryside)

            OTHER GUIDELINES:
            - This is a CHAPTER ILLUSTRATION PLATE, not a character portrait. Do not write a close-up, bust, head-and-shoulders, or window-profile headshot.
            - The setting must occupy most of the frame. Named people appear in the scene at three-quarter or full figure.
            - Focus on: setting, atmosphere, key objects, mood, and the people in the scene
            - Use the CAST names exactly when people appear. Do not replace them with "a girl" or "a young man"
            - When people appear, show visible faces with readable expressions — not silhouettes, not back-turned figures, not featureless shadows
            - Describe the scene as if it were a book illustration plate
            - Include lighting, time of day, weather if relevant
            - Keep it evocative and atmospheric rather than literal

            Start your prompt with this style prefix: %s
            Keep that exact medium for the whole prompt. Do not switch to cartoon, children's watercolor, anime, 3d render, or photoreal CGI unless the prefix is that medium.

            Respond with ONLY the image prompt, no explanation or other text. The prompt should be 50-150 words.
            """,
                bookTitle,
                author,
                chapterTitle,
                styleSettings.style(),
                settingContext,
                castBlock,
                truncateText(chapterContent, 2000),
                styleSettings.promptPrefix());

        try {
            String generatedPrompt = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.7)).trim();

            // Clean up the prompt - remove any quotes or extra formatting
            generatedPrompt = lockBookMedium(
                    ensureNarrativePlate(cleanPrompt(generatedPrompt)), styleSettings);

            log.info("Generated illustration prompt for chapter '{}': {}", chapterTitle,
                    truncateText(generatedPrompt, 100));

            return generatedPrompt;

        } catch (Exception e) {
            log.error("Failed to generate illustration prompt for chapter: {}", chapterTitle, e);
            // Return a fallback prompt using the style prefix
            return fallbackPrompt(bookTitle, author, chapterTitle, styleSettings, cast);
        }
    }

    private String fallbackPrompt(String bookTitle, String author, String chapterTitle,
                                  IllustrationSettings styleSettings, List<String> cast) {
        String featuring = cast == null || cast.isEmpty()
                ? ""
                : ", featuring " + String.join(" and ", cast);
        return lockBookMedium(ensureNarrativePlate(
                styleSettings.promptPrefix() + " a scene from " + bookTitle + " by " + author +
                        ", chapter " + chapterTitle + featuring + ", atmospheric book illustration"),
                styleSettings);
    }

    /** Keep the book's Imagine prefix at the front and a short medium lock at the end. */
    static String lockBookMedium(String prompt, IllustrationSettings styleSettings) {
        String text = prompt == null ? "" : prompt.trim();
        String prefix = styleSettings == null || styleSettings.promptPrefix() == null
                ? ""
                : styleSettings.promptPrefix().trim();
        if (!prefix.isEmpty() && !startsWithPrefix(text, prefix)) {
            text = text.isEmpty() ? prefix : prefix + (prefix.endsWith(" ") ? "" : " ") + text;
            text = text.trim();
        }
        String medium = "";
        if (styleSettings != null) {
            if (styleSettings.style() != null && !styleSettings.style().isBlank()) {
                medium = styleSettings.style().trim();
            } else if (!prefix.isEmpty()) {
                medium = prefix.replaceAll(",+$", "").trim();
            }
        }
        String lock = (medium.isEmpty() ? "" : " " + medium + ",") + MEDIUM_LOCK;
        if (!text.toLowerCase().contains("same medium as the rest of this book")) {
            text = text + lock;
        }
        return text.trim();
    }

    static boolean startsWithPrefix(String prompt, String prefix) {
        if (prompt == null || prefix == null || prefix.isBlank()) {
            return true;
        }
        String p = prefix.trim();
        String t = prompt.trim();
        if (t.regionMatches(true, 0, p, 0, p.length())) {
            return true;
        }
        String stripped = p.replaceAll(",+$", "").trim();
        return !stripped.isEmpty() && t.regionMatches(true, 0, stripped, 0, stripped.length());
    }

    /** Operator or LLM prompts that would otherwise read as a roster portrait. */
    public static String ensureNarrativePlate(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        if (text.isEmpty()) {
            text = "a full-page narrative book illustration of a chapter scene";
        }
        if (hasNarrativePlateGuard(text)) {
            return text;
        }
        return text + NARRATIVE_PLATE_GUARD;
    }

    static boolean hasNarrativePlateGuard(String prompt) {
        return prompt != null && prompt.toLowerCase().contains("not a character portrait");
    }

    /** Stored prompts written under the old no-faces / silhouette guideline. */
    public static boolean isSilhouetteEraPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        if (OLD_FACE_BAN.matcher(prompt).find() || OLD_SILHOUETTE_GUIDE.matcher(prompt).find()) {
            return true;
        }
        return SILHOUETTE_WORD.matcher(prompt).find() && !NOT_SILHOUETTE.matcher(prompt).find();
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private String cleanPrompt(String prompt) {
        // Remove surrounding quotes if present
        if (prompt.startsWith("\"") && prompt.endsWith("\"")) {
            prompt = prompt.substring(1, prompt.length() - 1);
        }
        // Remove any "Prompt:" or similar prefixes
        if (prompt.toLowerCase().startsWith("prompt:")) {
            prompt = prompt.substring(7).trim();
        }
        return prompt.trim();
    }
}
