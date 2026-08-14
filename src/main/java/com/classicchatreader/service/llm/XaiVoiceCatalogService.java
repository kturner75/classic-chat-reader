package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches the xAI voice roster ({@code GET /v1/tts/voices}) so TTS and character
 * voice selection can reason over every built-in voice, including ones released
 * after this code shipped. The list endpoint returns {@code voice_id} and
 * {@code name} only, so known personality/gender from the TTS docs overlay is
 * merged in. The merged roster is cached in memory and optionally on disk so
 * analysis does not depend on a live round-trip every restart. Never throws:
 * callers always get a usable roster.
 */
public class XaiVoiceCatalogService {

    private static final Logger log = LoggerFactory.getLogger(XaiVoiceCatalogService.class);
    private static final Duration FETCH_FAILURE_COOLDOWN = Duration.ofMinutes(5);

    public record XaiVoice(String id, String gender, String description) {}

    record DiskRoster(long fetchedAtEpochMs, List<XaiVoice> voices) {}

    /**
     * Personality overlay for the 21 flagship voices plus the original five,
     * taken from xAI TTS/voice docs and the published flagship roster.
     * Live API values win when the endpoint starts returning metadata.
     */
    static final Map<String, XaiVoice> KNOWN_VOICES = knownVoices();

    // Used whenever the catalog endpoint is unreachable and no disk cache exists.
    static final List<XaiVoice> FALLBACK_VOICES = List.copyOf(KNOWN_VOICES.values());

    private final WebClient webClient;
    private final String apiKey;
    private final String voicesUrl;
    private final int timeoutSeconds;
    private final Duration cacheTtl;
    private final XaiOAuthTokenManager oauthTokenManager;
    private final Path cacheFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile List<XaiVoice> cachedVoices;
    private volatile Instant cachedAt;
    private volatile Instant lastFetchFailureAt;

    public XaiVoiceCatalogService(String apiKey, String voicesUrl, int timeoutSeconds, int cacheTtlMinutes,
                                  XaiOAuthTokenManager oauthTokenManager) {
        this(apiKey, voicesUrl, timeoutSeconds, cacheTtlMinutes, oauthTokenManager, WebClient.builder().build(), null);
    }

    public XaiVoiceCatalogService(String apiKey, String voicesUrl, int timeoutSeconds, int cacheTtlMinutes,
                                  XaiOAuthTokenManager oauthTokenManager, String cacheFilePath) {
        this(apiKey, voicesUrl, timeoutSeconds, cacheTtlMinutes, oauthTokenManager, WebClient.builder().build(),
                cacheFilePath);
    }

    // Visible for testing: allows injecting a WebClient stubbed against a fake exchange function.
    XaiVoiceCatalogService(String apiKey, String voicesUrl, int timeoutSeconds, int cacheTtlMinutes,
                           XaiOAuthTokenManager oauthTokenManager, WebClient webClient) {
        this(apiKey, voicesUrl, timeoutSeconds, cacheTtlMinutes, oauthTokenManager, webClient, null);
    }

    XaiVoiceCatalogService(String apiKey, String voicesUrl, int timeoutSeconds, int cacheTtlMinutes,
                           XaiOAuthTokenManager oauthTokenManager, WebClient webClient, String cacheFilePath) {
        this.apiKey = apiKey;
        this.voicesUrl = voicesUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheTtl = Duration.ofMinutes(cacheTtlMinutes);
        this.oauthTokenManager = oauthTokenManager;
        this.webClient = webClient;
        this.cacheFile = (cacheFilePath != null && !cacheFilePath.isBlank()) ? Path.of(cacheFilePath) : null;
        log.info("xAI voice catalog service initialized: url={}, cacheTtlMinutes={}, cacheFile={}",
                voicesUrl, cacheTtlMinutes, this.cacheFile);
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
            DiskRoster disk = readDiskCache();
            if (disk != null && isFresh(Instant.ofEpochMilli(disk.fetchedAtEpochMs()))
                    && disk.voices() != null && !disk.voices().isEmpty()) {
                List<XaiVoice> enriched = enrich(disk.voices());
                cachedVoices = enriched;
                cachedAt = Instant.ofEpochMilli(disk.fetchedAtEpochMs());
                log.info("event=voice_catalog_loaded_from_file count={}", enriched.size());
                return enriched;
            }
            if (!hasCredentials()) {
                return rememberStaleOrFallback(disk);
            }
            Instant failedAt = lastFetchFailureAt;
            if (failedAt != null && Instant.now().isBefore(failedAt.plus(FETCH_FAILURE_COOLDOWN))) {
                return rememberStaleOrFallback(disk);
            }
            try {
                List<XaiVoice> fetched = enrich(fetchVoices());
                if (fetched.isEmpty()) {
                    throw new IllegalStateException("xAI voice catalog returned no usable voices");
                }
                cachedVoices = fetched;
                cachedAt = Instant.now();
                lastFetchFailureAt = null;
                writeDiskCache(fetched);
                log.info("event=voice_catalog_fetched count={}", fetched.size());
                return fetched;
            } catch (Exception e) {
                lastFetchFailureAt = Instant.now();
                log.warn("event=voice_catalog_fetch_failed error={} - serving {}", e.toString(),
                        cachedVoices != null || (disk != null && disk.voices() != null)
                                ? "stale cache" : "built-in fallback roster");
                return rememberStaleOrFallback(disk);
            }
        }
    }

    private List<XaiVoice> freshCachedVoices() {
        List<XaiVoice> cached = cachedVoices;
        Instant at = cachedAt;
        if (cached != null && at != null && isFresh(at)) {
            return cached;
        }
        return null;
    }

    private boolean isFresh(Instant fetchedAt) {
        return fetchedAt != null && Instant.now().isBefore(fetchedAt.plus(cacheTtl));
    }

    private List<XaiVoice> rememberStaleOrFallback(DiskRoster disk) {
        if (cachedVoices != null) {
            return cachedVoices;
        }
        if (disk != null && disk.voices() != null && !disk.voices().isEmpty()) {
            List<XaiVoice> enriched = enrich(disk.voices());
            cachedVoices = enriched;
            return enriched;
        }
        return FALLBACK_VOICES;
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
    // endpoint may grow - today it returns no tone metadata, so those usually stay null
    // until {@link #enrich(List)} overlays the documented personalities.
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

    List<XaiVoice> enrich(List<XaiVoice> voices) {
        List<XaiVoice> enriched = new ArrayList<>(voices.size());
        for (XaiVoice voice : voices) {
            XaiVoice known = KNOWN_VOICES.get(voice.id());
            if (known == null) {
                enriched.add(voice);
                continue;
            }
            String gender = voice.gender() != null ? voice.gender() : known.gender();
            String description = voice.description() != null ? voice.description() : known.description();
            enriched.add(new XaiVoice(voice.id(), gender, description));
        }
        return List.copyOf(enriched);
    }

    private DiskRoster readDiskCache() {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            return null;
        }
        try {
            DiskRoster roster = objectMapper.readValue(cacheFile.toFile(), DiskRoster.class);
            if (roster == null || roster.voices() == null || roster.voices().isEmpty()) {
                return null;
            }
            return roster;
        } catch (Exception e) {
            log.warn("event=voice_catalog_cache_read_failed error={}", e.toString());
            return null;
        }
    }

    private void writeDiskCache(List<XaiVoice> voices) {
        if (cacheFile == null) {
            return;
        }
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(cacheFile.toFile(), new DiskRoster(Instant.now().toEpochMilli(), voices));
        } catch (Exception e) {
            log.warn("event=voice_catalog_cache_write_failed error={}", e.toString());
        }
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

    private static Map<String, XaiVoice> knownVoices() {
        Map<String, XaiVoice> voices = new LinkedHashMap<>();
        put(voices, "ara", "female", "Warm, friendly and conversational");
        put(voices, "eve", "female", "Bright, energetic and expressive");
        put(voices, "leo", "male", "Authoritative and composed");
        put(voices, "rex", "male", "Deep, calm and steady");
        put(voices, "sal", "male", "Smooth and laid-back");
        put(voices, "altair", null, "Elegant, refined, and effortlessly premium");
        put(voices, "atlas", "male", "Confident, commanding, and reassuring");
        put(voices, "aurora", "female", "Luminous flagship voice for lyrical, atmospheric narration");
        put(voices, "carina", "female", "Soft, empathetic, and soothing");
        put(voices, "castor", "male", "Charismatic, down-to-earth, and easygoing");
        put(voices, "celeste", "female", "Compassionate, confident, and reassuring");
        put(voices, "cosmo", null, "Bright, curious, and easy to follow");
        put(voices, "helios", "male", "Upbeat, energetic, and endlessly versatile");
        put(voices, "helix", null, "Bold, dynamic, and adrenaline-fueled");
        put(voices, "iris", "female", "Friendly, upbeat, and naturally charming");
        put(voices, "kepler", null, "Inventive, forward-thinking, and charismatic");
        put(voices, "liora", "female", "Warm, radiant flagship voice");
        put(voices, "lumen", null, "Warm, articulate, and engaging");
        put(voices, "luna", "female", "Gentle, patient, and deeply nurturing");
        put(voices, "lux", null, "Grounded, calm, and quietly wise");
        put(voices, "naksh", "male", "Warm, thoughtful, and wise");
        put(voices, "orion", "male", "Rich, cinematic, and resonant");
        put(voices, "perseus", "male", "Strong, confident, and trustworthy");
        put(voices, "rigel", null, "Precise, professional, and calmly confident");
        put(voices, "sirius", null, "Quick-witted, clever, and playful");
        put(voices, "ursa", "female", "Friendly, warm, and steadfast");
        put(voices, "zagan", "male", "Powerful, dramatic, and unmistakable");
        put(voices, "zenith", null, "Sharp, focused, and driven");
        return Map.copyOf(voices);
    }

    private static void put(Map<String, XaiVoice> voices, String id, String gender, String description) {
        voices.put(id, new XaiVoice(id, gender, description));
    }
}
