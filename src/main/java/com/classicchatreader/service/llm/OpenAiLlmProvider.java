package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM provider implementation for OpenAI chat models.
 */
public class OpenAiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmProvider.class);

    private final WebClient webClient;
    private final String model;
    private final int timeoutSeconds;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiLlmProvider(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        log.info("OpenAI LLM provider initialized: baseUrl={}, model={}", baseUrl, model);
    }

    @Override
    public String generate(String prompt, LlmOptions options) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", options.temperature());

        if (options.topP() != null) {
            requestBody.put("top_p", options.topP());
        }
        if (options.maxTokens() != null) {
            requestBody.put("max_completion_tokens", options.maxTokens());
        }

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            JsonNode responseNode = objectMapper.readTree(response);
            JsonNode choices = responseNode.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }

            throw new LlmProviderException("Invalid response format from OpenAI API");

        } catch (WebClientResponseException e) {
            log.error("OpenAI API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmProviderException("OpenAI API error: " + e.getStatusCode(), e);
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate response from OpenAI", e);
            throw new LlmProviderException("Failed to generate response from OpenAI", e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("OpenAI not available: API key not configured");
            return false;
        }
        return true;
    }

    @Override
    public String getProviderName() {
        return "openai";
    }
}
