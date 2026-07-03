package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exchanges a long-lived xAI OAuth refresh token (obtained out-of-band via
 * scripts/xai-oauth-login.sh against a SuperGrok/X Premium+ subscription) for
 * short-lived access tokens, so xAI calls can draw against subscription quota
 * instead of pay-per-token API billing.
 *
 * The refresh token is provisioned manually per deployment; this class only
 * handles minting and caching access tokens from it. Callers must treat a
 * missing token (empty Optional) as "fall back to the API key" - this class
 * never throws for an unconfigured or dead refresh token.
 */
public class XaiOAuthTokenManager {

    private static final Logger log = LoggerFactory.getLogger(XaiOAuthTokenManager.class);
    private static final String TOKEN_URL = "https://auth.x.ai/oauth2/token";
    private static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
    // Refresh a bit before expiry so in-flight requests never race a live token, but bound the
    // skew to a fraction of the token's own lifetime - a flat skew larger than a short-lived
    // token's expires_in would make the cache permanently expired and force a synchronous
    // refresh (and endpoint round-trip) on every single call.
    private static final Duration MAX_REFRESH_SKEW = Duration.ofMinutes(5);
    // If a refresh attempt fails, don't hammer the endpoint on every subsequent call.
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(1);

    private record CachedToken(String accessToken, Instant expiresAt) {
    }

    private final WebClient webClient;
    private final String refreshToken;
    private final boolean enabled;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
    private volatile Instant lastFailureAt;

    public XaiOAuthTokenManager(String refreshToken, boolean enabled) {
        this(refreshToken, enabled, WebClient.builder().build());
    }

    // Visible for testing: allows injecting a WebClient stubbed against a fake exchange function.
    XaiOAuthTokenManager(String refreshToken, boolean enabled, WebClient webClient) {
        this.refreshToken = refreshToken;
        this.enabled = enabled;
        this.webClient = webClient;
        if (enabled && refreshToken != null && !refreshToken.isBlank()) {
            log.info("event=xai_oauth_configured (SuperGrok subscription auth enabled)");
        }
    }

    /**
     * Returns a valid access token if OAuth is configured and healthy, otherwise empty.
     * Empty means the caller should fall back to its xAI API key.
     */
    public synchronized Optional<String> getAccessToken() {
        if (!enabled || refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }

        CachedToken current = cachedToken.get();
        if (current != null && Instant.now().isBefore(current.expiresAt())) {
            return Optional.of(current.accessToken());
        }

        if (lastFailureAt != null && Instant.now().isBefore(lastFailureAt.plus(FAILURE_COOLDOWN))) {
            return Optional.empty();
        }

        return refresh();
    }

    /** Forces the next {@link #getAccessToken()} call to mint a fresh token. */
    public synchronized void invalidate() {
        cachedToken.set(null);
    }

    /** True if a refresh token is configured and enabled, without making a network call. */
    public boolean isConfigured() {
        return enabled && refreshToken != null && !refreshToken.isBlank();
    }

    private Optional<String> refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", CLIENT_ID);

        try {
            String response = webClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            JsonNode node = objectMapper.readTree(response);
            String accessToken = node.path("access_token").asText(null);
            int expiresInSeconds = node.path("expires_in").asInt(0);
            if (accessToken == null || expiresInSeconds <= 0) {
                throw new IllegalStateException("xAI OAuth token response missing access_token/expires_in");
            }

            Duration lifetime = Duration.ofSeconds(expiresInSeconds);
            Duration skew = lifetime.dividedBy(10).compareTo(MAX_REFRESH_SKEW) < 0
                    ? lifetime.dividedBy(10)
                    : MAX_REFRESH_SKEW;
            Instant expiresAt = Instant.now().plus(lifetime).minus(skew);
            cachedToken.set(new CachedToken(accessToken, expiresAt));
            lastFailureAt = null;
            log.info("event=xai_oauth_refreshed expires_in={}s", expiresInSeconds);
            return Optional.of(accessToken);

        } catch (Exception e) {
            lastFailureAt = Instant.now();
            cachedToken.set(null);
            log.warn("event=xai_oauth_refresh_failed error={}", e.getMessage());
            return Optional.empty();
        }
    }
}
