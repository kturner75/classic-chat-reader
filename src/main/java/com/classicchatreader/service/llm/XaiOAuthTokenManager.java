package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exchanges a long-lived xAI OAuth refresh token (obtained out-of-band via
 * scripts/xai-oauth-login.sh against a SuperGrok/X Premium+ subscription) for
 * short-lived access tokens, so xAI calls can draw against subscription quota
 * instead of pay-per-token API billing.
 *
 * xAI rotates refresh tokens on every use: each refresh_token grant response
 * carries a new refresh_token that invalidates the one just used. This class
 * tracks the current refresh token in memory and persists it to a local file
 * so a rotated token survives process restarts - without that, every restart
 * would resend the original (already-invalidated) token from the env var and
 * permanently fail with invalid_grant, as happened before this fix.
 *
 * Callers must treat a missing token (empty Optional) as "fall back to the
 * API key" - this class never throws for an unconfigured or dead refresh
 * token.
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

    // seedToken is the configured (env var) value the persisted currentToken was derived
    // from. Keying the cache on it lets an operator override a stale/bad persisted token
    // simply by rotating XAI_OAUTH_REFRESH_TOKEN and redeploying, per the recovery path
    // documented in scripts/xai_oauth_login.sh - without this, a persisted token that goes
    // bad (revoked, corrupted, copied from another deployment) would be stuck forever,
    // since it always wins over the configured value.
    private record PersistedState(String seedToken, String currentToken) {
    }

    private final WebClient webClient;
    private final AtomicReference<String> refreshToken = new AtomicReference<>();
    private final String seedRefreshToken;
    private final Path refreshTokenFile;
    private final boolean enabled;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
    private volatile Instant lastFailureAt;

    public XaiOAuthTokenManager(String refreshToken, boolean enabled, String refreshTokenFilePath) {
        this(refreshToken, enabled, refreshTokenFilePath, WebClient.builder().build());
    }

    // Visible for testing: allows injecting a WebClient stubbed against a fake exchange function.
    XaiOAuthTokenManager(String refreshToken, boolean enabled, String refreshTokenFilePath, WebClient webClient) {
        this.enabled = enabled;
        this.webClient = webClient;
        this.seedRefreshToken = refreshToken;
        this.refreshTokenFile = (refreshTokenFilePath != null && !refreshTokenFilePath.isBlank())
                ? Path.of(refreshTokenFilePath)
                : null;

        String initialToken = refreshToken;
        PersistedState persisted = readPersistedState();
        if (persisted != null && Objects.equals(persisted.seedToken(), refreshToken)) {
            initialToken = persisted.currentToken();
            log.info("event=xai_oauth_refresh_token_loaded_from_file");
        } else if (persisted != null) {
            log.info("event=xai_oauth_configured_token_overrides_stale_file");
        }
        this.refreshToken.set(initialToken);

        if (enabled && initialToken != null && !initialToken.isBlank()) {
            log.info("event=xai_oauth_configured (SuperGrok subscription auth enabled)");
        }
    }

    /**
     * Returns a valid access token if OAuth is configured and healthy, otherwise empty.
     * Empty means the caller should fall back to its xAI API key.
     */
    public synchronized Optional<String> getAccessToken() {
        if (!isConfigured()) {
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
        String token = refreshToken.get();
        return enabled && token != null && !token.isBlank();
    }

    // Visible for testing: exposes which refresh token would actually be sent, so tests can
    // verify the seed-vs-persisted-cache selection logic without inspecting network traffic.
    String currentRefreshTokenForTesting() {
        return refreshToken.get();
    }

    private Optional<String> refresh() {
        String currentRefreshToken = refreshToken.get();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", currentRefreshToken);
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

            // xAI rotates refresh tokens on every use - persist the new one so the next
            // process restart doesn't retry the now-invalidated token from config.
            String rotatedRefreshToken = node.path("refresh_token").asText(null);
            if (rotatedRefreshToken != null && !rotatedRefreshToken.isBlank()
                    && !rotatedRefreshToken.equals(currentRefreshToken)) {
                refreshToken.set(rotatedRefreshToken);
                persistRefreshToken(rotatedRefreshToken);
                log.info("event=xai_oauth_refresh_token_rotated");
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

        } catch (WebClientResponseException e) {
            lastFailureAt = Instant.now();
            cachedToken.set(null);
            log.warn("event=xai_oauth_refresh_failed status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            lastFailureAt = Instant.now();
            cachedToken.set(null);
            log.warn("event=xai_oauth_refresh_failed error={}", e.getMessage());
            return Optional.empty();
        }
    }

    private PersistedState readPersistedState() {
        if (refreshTokenFile == null || !Files.exists(refreshTokenFile)) {
            return null;
        }
        try {
            String raw = Files.readString(refreshTokenFile, StandardCharsets.UTF_8).trim();
            if (raw.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(raw);
            String seed = node.path("seedToken").asText(null);
            String current = node.path("currentToken").asText(null);
            if (current == null || current.isBlank()) {
                return null;
            }
            return new PersistedState(seed, current);
        } catch (IOException e) {
            log.warn("event=xai_oauth_refresh_token_file_read_failed error={}", e.getMessage());
            return null;
        }
    }

    private void persistRefreshToken(String token) {
        if (refreshTokenFile == null) {
            return;
        }
        try {
            if (refreshTokenFile.getParent() != null) {
                Files.createDirectories(refreshTokenFile.getParent());
            }
            String json = objectMapper.writeValueAsString(new PersistedState(seedRefreshToken, token));
            Path tmp = refreshTokenFile.resolveSibling(refreshTokenFile.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            restrictToOwnerOnly(tmp);
            Files.move(tmp, refreshTokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("event=xai_oauth_refresh_token_persist_failed error={}", e.getMessage());
        }
    }

    // This file holds a bearer credential equivalent to the one previously kept only in an
    // env var - lock it down to the owner so other local users/processes can't read it.
    private void restrictToOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows) - nothing to do.
        } catch (IOException e) {
            log.warn("event=xai_oauth_refresh_token_permissions_failed error={}", e.getMessage());
        }
    }
}
