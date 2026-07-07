package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile List<XaiVoice> cachedVoices;
    private volatile Instant cachedAt;
    private volatile Instant lastFetchFailureAt;

    public XaiVoiceCatalogService(String apiKey, String voicesUrl, int timeoutSeconds, int cacheTtlMinutes) {
        this(apiKey, voicesUrl, timeoutSeconds, cacheTtlMinutes, WebClient.builder().build());
    }

    // Visible for testing: allows injecting a WebClient stubbed against a fake exchange function.
    XaiVoiceCatalogService(String apiKey, String voicesUrl, int timeoutSeconds, int cacheTtlMinutes,
                           WebClient webClient) {
        this.apiKey = apiKey;
        this.voicesUrl = voicesUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheTtl = Duration.ofMinutes(cacheTtlMinutes);
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
            if (apiKey == null || apiKey.isBlank()) {
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

    private List<XaiVoice> fetchVoices() throws Exception {
        String response = webClient.get()
                .uri(voicesUrl)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        return parseVoices(objectMapper.readTree(response));
    }

    // Tolerant of the exact response shape: a bare array, or an object wrapping the
    // list under "voices" or "data", with per-voice fields under a few likely names.
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
            String id = firstText(item, "id", "name", "voice");
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
