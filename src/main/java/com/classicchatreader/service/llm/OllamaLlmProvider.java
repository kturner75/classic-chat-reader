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
import java.util.Map;

/**
 * LLM provider implementation for Ollama.
 * Calls the Ollama /api/generate endpoint.
 */
public class OllamaLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmProvider.class);

    private final WebClient webClient;
    private final String model;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OllamaLlmProvider(String baseUrl, String model, int timeoutSeconds) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        log.info("Ollama LLM provider initialized: baseUrl={}, model={}", baseUrl, model);
    }

    @Override
    public String generate(String prompt, LlmOptions options) {
        Map<String, Object> ollamaOptions = new HashMap<>();
        ollamaOptions.put("temperature", options.temperature());
        if (options.topP() != null) {
            ollamaOptions.put("top_p", options.topP());
        }
        if (options.maxTokens() != null) {
            ollamaOptions.put("num_predict", options.maxTokens());
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false,
                "options", ollamaOptions
        );

        try {
            String response = webClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            JsonNode responseNode = objectMapper.readTree(response);
            return responseNode.get("response").asText();

        } catch (WebClientResponseException e) {
            log.error("Ollama API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmProviderException("Ollama API error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Failed to generate response from Ollama", e);
            throw new LlmProviderException("Failed to generate response from Ollama", e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(2));
            return true;
        } catch (Exception e) {
            log.debug("Ollama not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }
}
