package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import com.classicchatreader.service.llm.XaiVoiceCatalogService;
import com.classicchatreader.service.llm.XaiVoiceCatalogService.XaiVoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Picks the most fitting xAI realtime voice for a character by asking the
 * reasoning LLM to match the character's description against the full voice
 * roster (fetched from xAI, so newly released voices are considered without a
 * code change). Falls back to the deterministic {@link CharacterVoiceAssigner}
 * heuristic whenever the LLM path is unavailable or returns garbage, so a call
 * can always be placed. Callers are expected to persist LLM picks; heuristic
 * picks are stable by construction and cheap to recompute.
 */
@Service
public class CharacterVoiceSelectionService {

    private static final Logger log = LoggerFactory.getLogger(CharacterVoiceSelectionService.class);

    private final LlmProvider reasoningProvider;
    private final XaiVoiceCatalogService voiceCatalog;
    private final CharacterVoiceAssigner fallbackAssigner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    public CharacterVoiceSelectionService(
            @Qualifier("reasoningLlmProvider") LlmProvider reasoningProvider,
            XaiVoiceCatalogService voiceCatalog,
            CharacterVoiceAssigner fallbackAssigner) {
        this.reasoningProvider = reasoningProvider;
        this.voiceCatalog = voiceCatalog;
        this.fallbackAssigner = fallbackAssigner;
        log.info("Character voice selection service initialized with provider: {}",
                reasoningProvider.getProviderName());
    }

    /**
     * @param fromLlm true when the voice came from the LLM pick (worth persisting);
     *                false when it came from the deterministic heuristic fallback.
     */
    public record VoiceSelection(String voice, String reasoning, boolean fromLlm) {}

    public VoiceSelection selectVoice(String name, String description) {
        if (cacheOnly || !reasoningProvider.isAvailable()) {
            return heuristicSelection(name, description);
        }
        List<XaiVoice> roster = voiceCatalog.getVoices();
        if (roster.isEmpty()) {
            return heuristicSelection(name, description);
        }

        try {
            String generatedText = reasoningProvider.generate(
                    buildPrompt(name, description, roster), LlmOptions.withTemperature(0.5));

            JsonNode node = objectMapper.readTree(extractJson(generatedText));
            String voice = node.has("voice") ? node.get("voice").asText("") : "";
            String reasoning = node.has("reasoning") ? node.get("reasoning").asText() : null;

            String rosterVoice = matchRosterVoice(voice, roster);
            if (rosterVoice == null) {
                log.warn("event=voice_selected source=heuristic character={} reason=off_roster_llm_pick voice={}",
                        name, voice);
                return heuristicSelection(name, description);
            }

            log.info("event=voice_selected source=llm character={} voice={} reasoning={}",
                    name, rosterVoice, reasoning);
            return new VoiceSelection(rosterVoice, reasoning, true);
        } catch (Exception e) {
            log.warn("event=voice_selection_llm_failed character={} error={}", name, e.toString());
            return heuristicSelection(name, description);
        }
    }

    private VoiceSelection heuristicSelection(String name, String description) {
        String voice = fallbackAssigner.assignVoice(name, description);
        log.info("event=voice_selected source=heuristic character={} voice={}", name, voice);
        return new VoiceSelection(voice, null, false);
    }

    // Case-insensitive validation against the roster, normalized to the catalog's casing.
    private String matchRosterVoice(String voice, List<XaiVoice> roster) {
        if (voice == null || voice.isBlank()) {
            return null;
        }
        return roster.stream()
                .map(XaiVoice::id)
                .filter(id -> id.equalsIgnoreCase(voice.trim()))
                .findFirst()
                .orElse(null);
    }

    private String buildPrompt(String name, String description, List<XaiVoice> roster) {
        String rosterDescription = roster.stream()
                .map(v -> String.format("- %s%s%s",
                        v.id(),
                        v.gender() != null ? " (" + v.gender() + ")" : "",
                        v.description() != null ? ": " + v.description() : ""))
                .collect(Collectors.joining("\n"));

        return String.format("""
                Select the best xAI realtime voice for a fictional book character who will speak on a live voice call.

                Character name: %s
                Character description:
                ---
                %s
                ---

                Available voices:
                %s

                Match the voice's gender and tone/personality to the character's gender, age, temperament, and social station. Prefer a distinctive match over a generic one.

                Respond with ONLY valid JSON in this exact format, no other text:
                {
                  "voice": "voice_id",
                  "reasoning": "One sentence on why this voice fits this character"
                }
                The "voice" value MUST be exactly one of the ids listed above.
                """, name, truncateText(description, 1500), rosterDescription);
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
