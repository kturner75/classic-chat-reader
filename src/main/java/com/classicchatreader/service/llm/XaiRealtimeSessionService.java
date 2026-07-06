package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Mints ephemeral client secrets for the xAI Realtime (voice agent) API so the
 * browser can open a WebSocket to wss://api.x.ai/v1/realtime directly without
 * ever seeing a long-lived credential.
 *
 * Auth mirrors {@link XaiLlmProvider}: prefer the SuperGrok OAuth access token,
 * fall back to the static API key. Because the SuperGrok subscription may not
 * cover the voice API at all, 402/403 (not just 401) trigger the API-key retry,
 * and a hard mint failure puts the service in a short cooldown so the frontend
 * sees voice calls as unavailable instead of failing on every attempt.
 */
public class XaiRealtimeSessionService {

    private static final Logger log = LoggerFactory.getLogger(XaiRealtimeSessionService.class);
    private static final String BASE_URL = "https://api.x.ai/v1";
    private static final Duration MINT_FAILURE_COOLDOWN = Duration.ofMinutes(5);

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final int tokenTtlSeconds;
    private final int timeoutSeconds;
    private final XaiOAuthTokenManager oauthTokenManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Instant lastMintFailureAt;

    public record RealtimeSession(String clientSecret, long expiresAtEpochSeconds, String model) {}

    public XaiRealtimeSessionService(String apiKey, String model, int tokenTtlSeconds,
                                     int timeoutSeconds, XaiOAuthTokenManager oauthTokenManager) {
        this(apiKey, model, tokenTtlSeconds, timeoutSeconds, oauthTokenManager,
                WebClient.builder().baseUrl(BASE_URL).build());
    }

    // Visible for testing: allows injecting a WebClient stubbed against a fake exchange function.
    XaiRealtimeSessionService(String apiKey, String model, int tokenTtlSeconds,
                              int timeoutSeconds, XaiOAuthTokenManager oauthTokenManager,
                              WebClient webClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.tokenTtlSeconds = tokenTtlSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.oauthTokenManager = oauthTokenManager;
        this.webClient = webClient;
        log.info("xAI realtime session service initialized: model={}, tokenTtlSeconds={}", model, tokenTtlSeconds);
    }

    public RealtimeSession mintSession() {
        Optional<String> oauthToken = oauthTokenManager != null
                ? oauthTokenManager.getAccessToken()
                : Optional.empty();
        boolean usingOAuth = oauthToken.isPresent();
        String bearerToken = oauthToken.orElse(apiKey);

        if (bearerToken == null || bearerToken.isBlank()) {
            throw new LlmProviderException(
                    "xAI realtime unavailable: OAuth token unavailable and no API key configured as fallback");
        }

        log.info("event=xai_realtime_mint auth_source={} model={}", usingOAuth ? "oauth" : "api_key", model);

        try {
            RealtimeSession session = callClientSecrets(bearerToken);
            lastMintFailureAt = null;
            return session;
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            // The voice API may reject OAuth subscription tokens (401) or not be
            // covered by the subscription (402/403) even when the token is valid.
            boolean oauthRejected = usingOAuth && (status == 401 || status == 402 || status == 403);
            if (oauthRejected && apiKey != null && !apiKey.isBlank()) {
                log.warn("event=xai_realtime_oauth_rejected status={} retrying_with=api_key", status);
                oauthTokenManager.invalidate();
                try {
                    RealtimeSession session = callClientSecrets(apiKey);
                    lastMintFailureAt = null;
                    log.info("event=xai_realtime_mint auth_source=api_key model={} reason=oauth_{}_fallback",
                            model, status);
                    return session;
                } catch (WebClientResponseException retryEx) {
                    throw mintFailure(retryEx);
                }
            }
            throw mintFailure(e);
        } catch (LlmProviderException e) {
            lastMintFailureAt = Instant.now();
            throw e;
        } catch (Exception e) {
            lastMintFailureAt = Instant.now();
            log.error("Failed to mint xAI realtime session", e);
            throw new LlmProviderException("Failed to mint xAI realtime session", e);
        }
    }

    private LlmProviderException mintFailure(WebClientResponseException e) {
        lastMintFailureAt = Instant.now();
        log.error("xAI realtime client_secrets error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
        return new LlmProviderException("xAI realtime client_secrets error: " + e.getStatusCode(), e);
    }

    private RealtimeSession callClientSecrets(String bearerToken) {
        String response = webClient.post()
                .uri("/realtime/client_secrets")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + bearerToken)
                .bodyValue(Map.of("expires_after", Map.of("seconds", tokenTtlSeconds)))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        try {
            JsonNode node = objectMapper.readTree(response);
            String secret = textAt(node, "value");
            if (secret == null && node.has("client_secret")) {
                secret = textAt(node.get("client_secret"), "value");
            }
            if (secret == null || secret.isBlank()) {
                throw new LlmProviderException("Invalid response format from xAI realtime client_secrets API");
            }

            long expiresAt = Instant.now().getEpochSecond() + tokenTtlSeconds;
            if (node.has("expires_at") && node.get("expires_at").isNumber()) {
                expiresAt = node.get("expires_at").asLong();
            } else if (node.has("client_secret") && node.get("client_secret").has("expires_at")
                    && node.get("client_secret").get("expires_at").isNumber()) {
                expiresAt = node.get("client_secret").get("expires_at").asLong();
            }

            return new RealtimeSession(secret, expiresAt, model);
        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmProviderException("Invalid response format from xAI realtime client_secrets API", e);
        }
    }

    private String textAt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * Config-level availability plus a cooldown after a failed mint, so a voice API
     * that turns out to be uncovered by the current credentials degrades gracefully.
     */
    public boolean isAvailable() {
        Instant failedAt = lastMintFailureAt;
        if (failedAt != null && Instant.now().isBefore(failedAt.plus(MINT_FAILURE_COOLDOWN))) {
            return false;
        }
        boolean hasOAuth = oauthTokenManager != null && oauthTokenManager.isConfigured();
        return hasOAuth || (apiKey != null && !apiKey.isBlank());
    }
}
