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
import java.util.Optional;

/**
 * LLM provider implementation for xAI (Grok).
 * Calls the xAI OpenAI-compatible /v1/chat/completions endpoint.
 *
 * When an {@link XaiOAuthTokenManager} is configured with a live SuperGrok
 * subscription token, requests use it (drawing against subscription quota)
 * and fall back to the static API key otherwise.
 */
public class XaiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(XaiLlmProvider.class);
    private static final String BASE_URL = "https://api.x.ai/v1";

    private final WebClient webClient;
    private final String model;
    private final int timeoutSeconds;
    private final String apiKey;
    private final XaiOAuthTokenManager oauthTokenManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public XaiLlmProvider(String apiKey, String model, int timeoutSeconds) {
        this(apiKey, model, timeoutSeconds, null);
    }

    public XaiLlmProvider(String apiKey, String model, int timeoutSeconds, XaiOAuthTokenManager oauthTokenManager) {
        this(apiKey, model, timeoutSeconds, oauthTokenManager, WebClient.builder().baseUrl(BASE_URL).build());
    }

    // Visible for testing: allows injecting a WebClient stubbed against a fake exchange function.
    XaiLlmProvider(String apiKey, String model, int timeoutSeconds, XaiOAuthTokenManager oauthTokenManager, WebClient webClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.oauthTokenManager = oauthTokenManager;
        this.webClient = webClient;
        log.info("xAI LLM provider initialized: model={}", model);
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
            requestBody.put("max_tokens", options.maxTokens());
        }

        Optional<String> oauthToken = oauthTokenManager != null
                ? oauthTokenManager.getAccessToken()
                : Optional.empty();
        boolean usingOAuth = oauthToken.isPresent();
        String bearerToken = oauthToken.orElse(apiKey);

        if (bearerToken == null || bearerToken.isBlank()) {
            log.warn("event=xai_auth_unavailable model={} oauthConfigured={} apiKeyConfigured={}",
                    model,
                    oauthTokenManager != null && oauthTokenManager.isConfigured(),
                    apiKey != null && !apiKey.isBlank());
            throw new LlmProviderException(
                    "xAI provider unavailable: OAuth token unavailable (refresh failed or not configured) "
                            + "and no API key configured as fallback");
        }

        log.info("event=xai_request auth_source={} model={}", usingOAuth ? "oauth" : "api_key", model);

        try {
            return callChatCompletions(requestBody, bearerToken);
        } catch (WebClientResponseException e) {
            if (usingOAuth && e.getStatusCode().value() == 401 && apiKey != null && !apiKey.isBlank()) {
                log.warn("event=xai_oauth_rejected model={} retrying_with=api_key", model);
                oauthTokenManager.invalidate();
                try {
                    String result = callChatCompletions(requestBody, apiKey);
                    log.info("event=xai_request auth_source=api_key model={} reason=oauth_401_fallback", model);
                    return result;
                } catch (WebClientResponseException retryEx) {
                    log.error("xAI API error: {} - {}", retryEx.getStatusCode(), retryEx.getResponseBodyAsString());
                    throw new LlmProviderException("xAI API error: " + retryEx.getStatusCode(), retryEx);
                }
            }
            log.error("xAI API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new LlmProviderException("xAI API error: " + e.getStatusCode(), e);
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate response from xAI", e);
            throw new LlmProviderException("Failed to generate response from xAI", e);
        }
    }

    private String callChatCompletions(Map<String, Object> requestBody, String bearerToken) {
        String response = webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + bearerToken)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        try {
            JsonNode responseNode = objectMapper.readTree(response);
            JsonNode choices = responseNode.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }
            throw new LlmProviderException("Invalid response format from xAI API");
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmProviderException("Invalid response format from xAI API", e);
        }
    }

    @Override
    public boolean isAvailable() {
        boolean hasOAuth = oauthTokenManager != null && oauthTokenManager.isConfigured();
        if (hasOAuth) {
            return true;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("xAI not available: no OAuth token and no API key configured");
            return false;
        }
        // For xAI, we assume availability if the API key is set
        // (avoid unnecessary health check calls)
        return true;
    }

    @Override
    public String getProviderName() {
        return "xai";
    }
}
