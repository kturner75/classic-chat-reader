package com.classicchatreader.service;

import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CharacterPortraitService {

    private static final Logger log = LoggerFactory.getLogger(CharacterPortraitService.class);

    private final LlmProvider reasoningProvider;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    public CharacterPortraitService(@Qualifier("reasoningLlmProvider") LlmProvider reasoningProvider) {
        this.reasoningProvider = reasoningProvider;
        log.info("Character portrait service initialized with provider: {}", reasoningProvider.getProviderName());
    }

    public String generatePortraitPrompt(
            String bookTitle,
            String author,
            String characterName,
            String characterDescription,
            IllustrationSettings bookStyle) {
        if (cacheOnly) {
            return buildFallbackPrompt(characterName, characterDescription, bookStyle);
        }

        String settingContext = bookStyle.setting() != null
                ? "Cultural/Geographic Setting: " + bookStyle.setting()
                : "";

        String prompt = String.format("""
            Generate an image prompt for a character portrait in the style of a classic book illustration.

            Book: %s by %s
            Character: %s
            Character Description: %s
            Visual Style: %s
            %s

            Create a portrait prompt that:
            - Captures the character's essence, personality, and social standing
            - Uses the artistic style: %s
            - Shows the character from chest/shoulders up, in a 3/4 view or profile
            - Includes period-appropriate clothing, hair, and accessories for the book's setting
            - Has an atmospheric, painterly quality suitable for a book illustration
            - Suggests mood through lighting and background elements
            - Does NOT show a direct frontal face view
            - Maintains cultural accuracy for the book's setting

            Start your prompt with this style prefix: %s

            Respond with ONLY the image prompt, 50-100 words, no explanation.
            """,
                bookTitle,
                author,
                characterName,
                characterDescription,
                bookStyle.style(),
                settingContext,
                bookStyle.style(),
                bookStyle.promptPrefix());

        try {
            String generatedPrompt = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.7)).trim();

            generatedPrompt = cleanPrompt(generatedPrompt);

            log.info("Generated portrait prompt for '{}': {}", characterName,
                    truncateText(generatedPrompt, 100));

            return generatedPrompt;

        } catch (Exception e) {
            log.error("Failed to generate portrait prompt for character: {}", characterName, e);
            return buildFallbackPrompt(characterName, characterDescription, bookStyle);
        }
    }

    private String buildFallbackPrompt(String characterName, String description,
                                       IllustrationSettings bookStyle) {
        return bookStyle.promptPrefix() + " portrait of " + characterName +
                ", " + truncateText(description, 50) +
                ", atmospheric book illustration, 3/4 view, period clothing";
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private String cleanPrompt(String prompt) {
        if (prompt.startsWith("\"") && prompt.endsWith("\"")) {
            prompt = prompt.substring(1, prompt.length() - 1);
        }
        if (prompt.toLowerCase().startsWith("prompt:")) {
            prompt = prompt.substring(7).trim();
        }
        return prompt.trim();
    }
}
