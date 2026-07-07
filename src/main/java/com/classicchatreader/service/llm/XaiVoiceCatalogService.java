package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fetches the xAI voice roster (GET /v1/tts/voices) so voice selection can reason
 * over all available voices - including ones released after this code shipped -
 * instead of a hardcoded list. The roster is fetched lazily, cached in memory with
 * a TTL, and degrades to the five original built-in voices when the endpoint is
 * unreachable. Never throws: callers always get a usable roster.
 */
public class XaiVoiceCatalogService {

    private static final Logger log = LoggerFactory.getLogger(XaiVoiceCatalogService.class);
    private static final Duration FETCH_FAILURE_COOLDOWN = Duration.ofMinutes(5);

    public record XaiVoice(String id, String gender, String description) {}

    // The original five voices, used whenever the catalog endpoint is unavailable.
    static final List<XaiVoice> FALLBACK_VOICES = List.of(
            new XaiVoice("ara", "female", "Warm, friendly and conversational"),
            new XaiVoice("eve", "female", "Bright, energetic and expressive"),
            new XaiVoice("leo", "male", "Authoritative and composed"),
            new XaiVoice("rex", "male", "Deep, calm and steady"),
            new XaiVoice("sal", "male", "Smooth and laid-back"));

    private final WebClient webClient;
    private final String apiKey;
    private final String voicesUrl;
    private final int timeoutSeconds;
    private final Duration cacheTtl;
    private final XaiOAuthTokenManager oauthTokenManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile List<XaiVoice> cachedVoices;
    private volatile Instant cachedAt;
    private volatile Instant lastFetchFailureAt;

    public XaiVoiceCatalogService(String apiKey, String voicesUrl, int timeoutSeconds, int cacheTtlMinutes,
                                  XaiOAuthTokenManager oauthTokenManager) {
        this(apiKey, voicesUrl, timeoutSeconds, cacheTtlMinutes, oauthTokenManager, WebClient.builder().build());
    }

    // Visible for testing: allows injecting a WebClient stubbed against a fake exchange function.
    XaiVoiceCatalogService(String apiKey, String voicesUrl, int timeoutSeconds, int cacheTtlMinutes,
                           XaiOAuthTokenManager oauthTokenManager, WebClient webClient) {
        this.apiKey = apiKey;
        this.voicesUrl = voicesUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheTtl = Duration.ofMinutes(cacheTtlMinutes);
        this.oauthTokenManager = oauthTokenManager;
        this.webClient = webClient;
        log.info("xAI voice catalog service initialized: url={}, cacheTtlMinutes={}", voicesUrl, cacheTtlMinutes);
    }

    /**
     * Returns the voice roster, fetching/refreshing it if needed. Never throws and
     * never returns an empty list: on failure it serves the last good roster, or
     * the built-in fallback if nothing was ever fetched.
     */
    public List<XaiVoice> getVoices() {
        List<XaiVoice> fresh = freshCachedVoices();
        if (fresh != null) {
            return fresh;
        }
        synchronized (this) {
            fresh = freshCachedVoices();
            if (fresh != null) {
                return fresh;
            }
            if (!hasCredentials()) {
                return staleOrFallback();
            }
            Instant failedAt = lastFetchFailureAt;
            if (failedAt != null && Instant.now().isBefore(failedAt.plus(FETCH_FAILURE_COOLDOWN))) {
                return staleOrFallback();
            }
            try {
                List<XaiVoice> fetched = fetchVoices();
                if (fetched.isEmpty()) {
                    throw new IllegalStateException("xAI voice catalog returned no usable voices");
                }
                cachedVoices = fetched;
                cachedAt = Instant.now();
                lastFetchFailureAt = null;
                log.info("event=voice_catalog_fetched count={}", fetched.size());
                return fetched;
            } catch (Exception e) {
                lastFetchFailureAt = Instant.now();
                log.warn("event=voice_catalog_fetch_failed error={} - serving {}", e.toString(),
                        cachedVoices != null ? "stale cache" : "built-in fallback roster");
                return staleOrFallback();
            }
        }
    }

    private List<XaiVoice> freshCachedVoices() {
        List<XaiVoice> cached = cachedVoices;
        Instant at = cachedAt;
        if (cached != null && at != null && Instant.now().isBefore(at.plus(cacheTtl))) {
            return cached;
        }
        return null;
    }

    private List<XaiVoice> staleOrFallback() {
        List<XaiVoice> cached = cachedVoices;
        return cached != null ? cached : FALLBACK_VOICES;
    }

    private boolean hasCredentials() {
        boolean hasOAuth = oauthTokenManager != null && oauthTokenManager.isConfigured();
        return hasOAuth || (apiKey != null && !apiKey.isBlank());
    }

    // Auth mirrors XaiRealtimeSessionService: prefer the SuperGrok OAuth token, fall back
    // to the static API key, and retry with the key on 401/402/403 because the OAuth
    // subscription may not cover this API even when the token itself is valid.
    private List<XaiVoice> fetchVoices() throws Exception {
        Optional<String> oauthToken = oauthTokenManager != null
                ? oauthTokenManager.getAccessToken()
                : Optional.empty();
        boolean usingOAuth = oauthToken.isPresent();
        String bearerToken = oauthToken.orElse(apiKey);

        try {
            return callVoices(bearerToken);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean oauthRejected = usingOAuth && (status == 401 || status == 402 || status == 403);
            if (oauthRejected && apiKey != null && !apiKey.isBlank()) {
                log.warn("event=voice_catalog_oauth_rejected status={} retrying_with=api_key", status);
                oauthTokenManager.invalidate();
                return callVoices(apiKey);
            }
            throw e;
        }
    }

    private List<XaiVoice> callVoices(String bearerToken) throws Exception {
        String response = webClient.get()
                .uri(voicesUrl)
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        return parseVoices(objectMapper.readTree(response));
    }

    // Documented shape: {"voices":[{"voice_id":"ara","name":"Ara","language":"en"}]} where
    // voice_id is the canonical id for the Realtime "voice" param and name is display-only.
    // Stays tolerant of variations: a bare array or a "data" wrapper, alternate id fields
    // (display name only as a last resort), and optional description/gender fields that the
    // endpoint may grow - today it returns no tone metadata, so those usually stay null.
    private List<XaiVoice> parseVoices(JsonNode root) {
        JsonNode list = root;
        if (root.isObject()) {
            if (root.has("voices") && root.get("voices").isArray()) {
                list = root.get("voices");
            } else if (root.has("data") && root.get("data").isArray()) {
                list = root.get("data");
            }
        }
        if (!list.isArray()) {
            return List.of();
        }

        List<XaiVoice> voices = new ArrayList<>();
        for (JsonNode item : list) {
            String id = firstText(item, "voice_id", "id", "voice", "name");
            if (id == null || id.isBlank()) {
                continue;
            }
            String description = firstText(item, "description", "preview_text", "tone");
            String gender = firstText(item, "gender");
            if (gender == null && item.has("labels")) {
                gender = firstText(item.get("labels"), "gender");
            }
            voices.add(new XaiVoice(id.toLowerCase(Locale.ROOT), gender, description));
        }
        return voices;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
