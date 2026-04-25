package com.classicchatreader.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CharacterExtractionService {

    private static final Logger log = LoggerFactory.getLogger(CharacterExtractionService.class);
    private static final Set<String> NAME_TITLES = Set.of(
            "mr", "mrs", "ms", "miss", "lady", "lord", "sir", "madam", "madame",
            "mme", "mlle", "dr", "doctor", "prof", "professor", "rev", "reverend",
            "capt", "captain", "col", "colonel", "major"
    );

    private final LlmProvider reasoningProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    @Value("${character.extraction.max-characters-per-chapter:5}")
    private int maxCharactersPerChapter;

    public record ExtractedCharacter(
        String name,
        String description,
        int approximateParagraphIndex
    ) {}

    public CharacterExtractionService(@Qualifier("reasoningLlmProvider") LlmProvider reasoningProvider) {
        this.reasoningProvider = reasoningProvider;
        log.info("Character extraction service initialized with provider: {}", reasoningProvider.getProviderName());
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

    public List<ExtractedCharacter> extractCharactersFromChapter(
            String bookTitle,
            String author,
            String chapterTitle,
            String chapterContent,
            List<String> existingCharacterNames) {
        if (cacheOnly) {
            log.info("Skipping character extraction in cache-only mode for chapter '{}'", chapterTitle);
            return List.of();
        }

        String existingCharactersList = existingCharacterNames.isEmpty()
                ? "(none yet)"
                : existingCharacterNames.stream()
                    .map(name -> "- " + name)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("(none yet)");

        String prompt = buildExtractionPrompt(
                bookTitle,
                author,
                chapterTitle,
                chapterContent,
                existingCharactersList
        );

        try {
            String generatedText = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.3));
            JsonNode charactersArray = parseCharactersArray(generatedText, chapterTitle);

            Set<String> normalizedExisting = existingCharacterNames.stream()
                    .map(this::normalizeName)
                    .filter(normalized -> !normalized.isBlank())
                    .collect(Collectors.toSet());

            List<ExtractedCharacter> characters = new ArrayList<>();
            for (JsonNode charNode : charactersArray) {
                String name = charNode.get("name").asText();
                String description = charNode.has("description")
                        ? charNode.get("description").asText()
                        : "A character in the story";
                int paragraphIndex = charNode.has("approximateParagraphIndex")
                        ? charNode.get("approximateParagraphIndex").asInt(0)
                        : 0;

                // Skip if name matches existing character (case-insensitive)
                String normalizedName = normalizeName(name);
                boolean isDuplicate = normalizedName.isBlank() || normalizedExisting.contains(normalizedName);
                if (!isDuplicate && !name.isBlank()) {
                    characters.add(new ExtractedCharacter(name, description, paragraphIndex));
                }
            }

            log.info("Extracted {} new characters from chapter '{}'", characters.size(), chapterTitle);
            return characters;

        } catch (Exception e) {
            log.error("Failed to extract characters from chapter '{}'", chapterTitle, e);
            throw new IllegalStateException("Character extraction failed for chapter '" + chapterTitle + "'", e);
        }
    }

    private String buildExtractionPrompt(
            String bookTitle,
            String author,
            String chapterTitle,
            String chapterContent,
            String existingCharactersList) {
        return String.format("""
            Analyze this chapter and identify any NEW characters that are introduced.
            A character is someone who:
            - Has a clear, specific name (no generic roles like "the maid" or "a stranger")
            - Appears as a distinct person in the narrative
            - Engages in non-trivial dialogue (more than a one-line exchange)
            - Is NOT already in the known characters list below

            Book: %s by %s
            Chapter: %s

            Already known characters (DO NOT include these):
            %s

            Chapter content:
            ---
            %s
            ---

            IMPORTANT:
            - Only include genuinely NEW characters not in the list above
            - Do not add alternate forms of existing names (e.g., titles, last-name-only variants)
            - If a name could be confused with an existing character, omit it
            - Be conservative: if you're unsure, leave the character out
            - Maximum %d new characters per chapter
            - Do not include historical figures mentioned in passing
            - Do not include groups of people (e.g., "the crowd") unless one individual stands out
            - Estimate which paragraph (0-indexed) the character first appears in

            Respond with ONLY valid JSON array, no other text:
            [
              {
                "name": "Character Name",
                "description": "2-3 sentence description of who they are, their role, and notable traits",
                "approximateParagraphIndex": 5
              }
            ]

            If no new characters are introduced, respond with: []
            """,
                bookTitle, author, chapterTitle,
                existingCharactersList,
                truncateText(chapterContent, 3000),
                maxCharactersPerChapter);
    }

    private JsonNode parseCharactersArray(String generatedText, String chapterTitle) throws JsonProcessingException {
        String json = extractJsonArray(generatedText);
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException firstFailure) {
            log.warn("Character extraction returned invalid JSON for chapter '{}'; attempting repair", chapterTitle);
            String repairedText = reasoningProvider.generate(buildRepairPrompt(generatedText), LlmOptions.withTemperature(0.0));
            String repairedJson = extractJsonArray(repairedText);
            try {
                return objectMapper.readTree(repairedJson);
            } catch (JsonProcessingException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                throw repairFailure;
            }
        }
    }

    private String buildRepairPrompt(String malformedResponse) {
        return """
                Convert the following malformed model output into a valid JSON array.
                Return ONLY valid JSON.
                Preserve the intended fields:
                - name
                - description
                - approximateParagraphIndex

                If the content cannot be repaired confidently, return [].

                Malformed output:
                ---
                """ + truncateText(malformedResponse, 2500) + """
                ---
                """;
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private String extractJsonArray(String text) {
        // Find JSON array in the response
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        // If no array found, check if it's an empty response
        if (text.trim().equalsIgnoreCase("[]") || text.contains("no new characters")) {
            return "[]";
        }
        log.warn("No JSON array found in response, returning empty: {}", text);
        return "[]";
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.toLowerCase()
                .replaceAll("[^a-z\\s-]", " ")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        List<String> parts = Arrays.stream(cleaned.split(" "))
                .filter(part -> !part.isBlank())
                .collect(Collectors.toList());
        while (!parts.isEmpty() && NAME_TITLES.contains(parts.get(0))) {
            parts.remove(0);
        }
        return String.join(" ", parts).trim();
    }
}
