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

import java.util.stream.Collectors;

@Service
public class VoiceAnalysisService {

  private static final Logger log = LoggerFactory.getLogger(VoiceAnalysisService.class);

  private final LlmProvider reasoningProvider;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${generation.cache-only:false}")
  private boolean cacheOnly;

  public VoiceAnalysisService(@Qualifier("reasoningLlmProvider") LlmProvider reasoningProvider) {
    this.reasoningProvider = reasoningProvider;
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
      return VoiceSettings.defaults();
    }
    String voicesDescription = TtsService.AVAILABLE_VOICES.stream()
        .map(v -> String.format("- %s (%s): %s", v.get("id"), v.get("gender"), v.get("description")))
        .collect(Collectors.joining("\n"));

    String prompt = String.format("""
                                      Analyze this book and recommend the best text-to-speech voice for an audiobook experience.
                                      
                                      Book Title: %s
                                      Author: %s
                                      Opening Text:
                                      ---
                                      %s
                                      ---
                                      
                                      Available voices (USE VARIETY - match the voice to the book's unique character):
                                      %s
                                      
                                      VOICE MATCHING GUIDELINES - choose the voice that best fits the book's essence:
                                      - onyx: Deep masculine American voice - horror, thriller, gothic, epic tales, dark atmospheric works (Dracula, Frankenstein, Dostoevsky)
                                      - ash: Deep masculine American voice - adventure, war stories, action-driven narratives
                                      - ballad: British masculine voice, smooth - British literary fiction, period dramas, folk tales
                                      - fable: British feminine voice, warm - classic romance, Austen, Bronte sisters, Victorian literature
                                      - sage: Expressive feminine voice - dialog-heavy stories, plays, mysteries with strong characters
                                      - shimmer: Gentle feminine voice - gentle stories, children's classics, peaceful narratives
                                      - verse: Clear masculine American voice - general audiobook style, non-fiction, essays, default choice
                                      
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
                                      
                                      Speed: 0.85-0.95 for dense/atmospheric prose, 1.0-1.1 for action/thrillers.
                                      """, title, author, truncateText(openingText, 1500), voicesDescription);

    try {
      String generatedText = reasoningProvider.generate(prompt, LlmOptions.withTemperature(0.5));

      // Extract JSON from response (LLM might include extra text)
      String json = extractJson(generatedText);
      JsonNode settingsNode = objectMapper.readTree(json);

      return new VoiceSettings(
          settingsNode.get("voice").asText("fable"),
          settingsNode.has("speed") ? settingsNode.get("speed").asDouble(1.0) : 1.0,
          settingsNode.has("instructions") ? settingsNode.get("instructions").asText() : null,
          settingsNode.has("reasoning") ? settingsNode.get("reasoning").asText() : "AI recommended"
      );

    } catch (Exception e) {
      log.error("Failed to analyze book for voice settings", e);
      return VoiceSettings.defaults();
    }
  }

  private String truncateText(String text, int maxLength) {
    if (text == null) return "";
    if (text.length() <= maxLength) return text;
    return text.substring(0, maxLength) + "...";
  }

  private String extractJson(String text) {
    // Find JSON object in the response
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start >= 0 && end > start) {
      return text.substring(start, end + 1);
    }
    throw new IllegalArgumentException("No JSON found in response: " + text);
  }
}
