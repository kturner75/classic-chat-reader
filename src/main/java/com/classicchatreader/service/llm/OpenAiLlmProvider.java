package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        this(apiKey, model, timeoutSeconds, WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build());
        log.info("OpenAI LLM provider initialized: baseUrl={}, model={}", baseUrl, model);
    }

    OpenAiLlmProvider(String apiKey, String model, int timeoutSeconds, WebClient webClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.webClient = webClient;
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
            return callChatCompletions(requestBody);
        } catch (WebClientResponseException.BadRequest e) {
            // Reasoning-tier models (o1/o3/o-series, and apparently gpt-5.5) reject
            // custom temperature/top_p and only accept the default. Rather than
            // hardcode a model allowlist that will go stale, detect that specific
            // rejection and retry once with sampling params stripped.
            String unsupportedParam = unsupportedSamplingParam(e);
            if (unsupportedParam != null && requestBody.containsKey(unsupportedParam)) {
                log.warn("event=openai_unsupported_sampling_param model={} param={} retrying_without_it",
                        model, unsupportedParam);
                Map<String, Object> retryBody = new HashMap<>(requestBody);
                retryBody.remove("temperature");
                retryBody.remove("top_p");
                try {
                    return callChatCompletions(retryBody);
                } catch (WebClientResponseException retryEx) {
                    log.error("OpenAI API error: {} - {}", retryEx.getStatusCode(), retryEx.getResponseBodyAsString());
                    throw new LlmProviderException("OpenAI API error: " + retryEx.getStatusCode(), retryEx);
                }
            }
            log.error("OpenAI API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmProviderException("OpenAI API error: " + e.getStatusCode(), e);
        } catch (WebClientResponseException e) {
            log.error("OpenAI API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmProviderException("OpenAI API error: " + e.getStatusCode(), e);
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            if (LlmProviderException.isTransient(e)) {
                log.warn("Transient failure calling OpenAI: {}", e.toString());
            } else {
                log.error("Failed to generate response from OpenAI", e);
            }
            throw new LlmProviderException("Failed to generate response from OpenAI", e);
        }
    }

    private String callChatCompletions(Map<String, Object> requestBody) {
        String response = LlmWebClientSupport.postJson(
                webClient.post().uri("/chat/completions"),
                requestBody,
                Duration.ofSeconds(timeoutSeconds),
                "openai");

        JsonNode responseNode;
        try {
            responseNode = objectMapper.readTree(response);
        } catch (Exception e) {
            throw new LlmProviderException("Invalid response format from OpenAI API", e);
        }
        JsonNode choices = responseNode.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode message = choices.get(0).get("message");
            if (message != null && message.has("content")) {
                return message.get("content").asText();
            }
        }
        throw new LlmProviderException("Invalid response format from OpenAI API");
    }

    /**
     * Returns "temperature" or "top_p" if the error body is OpenAI's
     * unsupported_value rejection for that param, otherwise null.
     */
    private String unsupportedSamplingParam(WebClientResponseException e) {
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString()).get("error");
            if (error == null) {
                return null;
            }
            String code = error.has("code") ? error.get("code").asText() : null;
            String param = error.has("param") ? error.get("param").asText() : null;
            if ("unsupported_value".equals(code) && ("temperature".equals(param) || "top_p".equals(param))) {
                return param;
            }
        } catch (Exception ignored) {
            // Not parseable as the expected error shape - fall through to the normal error path.
        }
        return null;
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
