package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.model.VoiceSettings;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class VoiceAnalysisService {

  private static final Logger log = LoggerFactory.getLogger(VoiceAnalysisService.class);

  private final LlmProvider reasoningProvider;
  private final TtsService ttsService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${generation.cache-only:false}")
  private boolean cacheOnly;

  public VoiceAnalysisService(@Qualifier("reasoningLlmProvider") LlmProvider reasoningProvider,
                              TtsService ttsService) {
    this.reasoningProvider = reasoningProvider;
    this.ttsService = ttsService;
    log.info("Voice analysis service initialized with provider: {}", reasoningProvider.getProviderName());
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

  public VoiceSettings analyzeBookForVoice(String title, String author, String openingText) {
    if (cacheOnly) {
      log.info("Skipping voice analysis in cache-only mode for '{}'", title);
      return fallbackVoiceSettings();
    }
    String voicesDescription = formatVoicesForAnalysis(ttsService.listVoices());

    String prompt = """
        Analyze this book and recommend the best text-to-speech voice for an audiobook experience.

        Book Title: {title}
        Author: {author}
        Opening Text:
        ---
        {opening}
        ---

        Available voices from the current TTS provider ({provider}). Choose from this
        roster only — do not use voice names from any other TTS provider:
        {voices}

        Choose exactly one voice_id from the list above. Match gender, tone, and description
        to the book's narrator, genre, period, and mood. Do not invent voice ids.

        Matching rules:
        - Infer the narrator from the opening text (first-person gender, social station, mood).
        - Dense philosophical, theological, or tragic novels (Dostoevsky, Tolstoy, Melville)
          need a serious male literary voice such as naksh, orion, rex, atlas, or lux.
        - Gothic, revenge, horror, or occult narration should use zagan, orion, rex, or perseus.
        - ara and eve are warm conversational female voices. Use them only for clearly
          female-narrated light, romantic, or children's work — not for dark or epic fiction.
        - Prefer a distinctive flagship voice over the original five (ara, eve, leo, rex, sal)
          when the story has a strong tone.
          - If no voice is a clear match, use {defaultVoice}.

        Consider the book's:
        - Genre and emotional tone
        - Time period and setting
        - Narrative voice and style
        - Target audience and mood

        Respond with ONLY valid JSON in this exact format, no other text:
        {
          "voice": "voice_id",
          "speed": 0.95,
          "instructions": "Specific guidance on delivery style, emotion, and pacing for this particular book",
          "reasoning": "Why this voice matches this book's unique character"
        }

        Speed: 0.85-0.95 for dense/atmospheric prose, 1.0-1.2 for action/thrillers. Range is 0.7-1.5.
        """
        .replace("{title}", title == null ? "" : title)
        .replace("{author}", author == null ? "" : author)
        .replace("{opening}", truncateText(openingText, 1500))
        .replace("{voices}", voicesDescription)
        .replace("{provider}", ttsService.currentProvider())
        .replace("{defaultVoice}", ttsService.defaultVoice());

    try {
      String generatedText = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.5));

      String json = extractJson(generatedText);
      JsonNode settingsNode = objectMapper.readTree(json);

      String voice = ttsService.resolveAnalyzedVoice(
          settingsNode.has("voice") ? settingsNode.get("voice").asText() : null);
      double speed = ttsService.clampSpeed(
          settingsNode.has("speed") ? settingsNode.get("speed").asDouble(1.0) : 1.0);

      log.info("event=tts_voice_analyzed title={} voice={} speed={} provider={} reasoning={}",
          title, voice, speed, ttsService.currentProvider(),
          settingsNode.has("reasoning") ? settingsNode.get("reasoning").asText() : null);

      return new VoiceSettings(
          voice,
          speed,
          settingsNode.has("instructions") ? settingsNode.get("instructions").asText() : null,
          settingsNode.has("reasoning") ? settingsNode.get("reasoning").asText() : "AI recommended",
          ttsService.currentProvider()
      );

    } catch (Exception e) {
      log.error("Failed to analyze book for voice settings", e);
      return fallbackVoiceSettings();
    }
  }

  private String formatVoicesForAnalysis(java.util.List<Map<String, String>> voices) {
    StringBuilder male = new StringBuilder();
    StringBuilder female = new StringBuilder();
    StringBuilder other = new StringBuilder();
    for (Map<String, String> voice : voices) {
      String line = formatVoiceLine(voice) + "\n";
      String gender = voice.getOrDefault("gender", "").toLowerCase();
      if (gender.startsWith("m")) {
        male.append(line);
      } else if (gender.startsWith("f")) {
        female.append(line);
      } else {
        other.append(line);
      }
    }
    return """
        Male voices:
        %s
        Female voices:
        %s
        Other / unspecified:
        %s
        """.formatted(male, female, other);
  }

  private String formatVoiceLine(Map<String, String> voice) {
    String id = voice.getOrDefault("id", "");
    String gender = voice.getOrDefault("gender", "");
    String description = voice.getOrDefault("description", "");
    if (!gender.isBlank() && !description.isBlank()) {
      return "- " + id + " (" + gender + "): " + description;
    }
    if (!description.isBlank()) {
      return "- " + id + ": " + description;
    }
    if (!gender.isBlank()) {
      return "- " + id + " (" + gender + ")";
    }
    return "- " + id;
  }

  private String truncateText(String text, int maxLength) {
    if (text == null) return "";
    if (text.length() <= maxLength) return text;
    return text.substring(0, maxLength) + "...";
  }

  private VoiceSettings fallbackVoiceSettings() {
    String voice = ttsService.defaultVoice();
    if (voice == null || voice.isBlank()) {
      voice = "orion";
    }
    return new VoiceSettings(voice, 1.0, null, "Default settings", ttsService.currentProvider());
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
