package com.classicchatreader.service;

import com.classicchatreader.model.VoiceSettings;
import com.classicchatreader.service.llm.XaiOAuthTokenManager;
import com.classicchatreader.service.llm.XaiVoiceCatalogService;
import com.classicchatreader.service.llm.XaiVoiceCatalogService.XaiVoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final String BASE_URL = "https://api.x.ai/v1";
    static final String PROVIDER_XAI = "xai";
    static final double MIN_SPEED = 0.7;
    static final double MAX_SPEED = 1.5;

    private final String apiKey;
    private final String defaultVoice;
    private final int timeoutSeconds;
    private final boolean cacheOnly;
    private final String language;
    private final XaiVoiceCatalogService voiceCatalog;
    private final XaiOAuthTokenManager oauthTokenManager;
    private final WebClient webClient;
    private final Path cachePath;

    @Autowired
    public TtsService(
            @Value("${tts.xai.api-key:${voice.call.xai.api-key:${ai.chat.xai.api-key:}}}") String apiKey,
            @Value("${tts.xai.default-voice:orion}") String defaultVoice,
            @Value("${tts.cache-dir}") String cacheDir,
            @Value("${tts.xai.timeout-seconds:45}") int timeoutSeconds,
            @Value("${tts.cache-only:false}") boolean cacheOnly,
            @Value("${tts.xai.language:en}") String language,
            XaiVoiceCatalogService voiceCatalog,
            XaiOAuthTokenManager oauthTokenManager) {
        this(apiKey, defaultVoice, cacheDir, timeoutSeconds, cacheOnly, language, voiceCatalog, oauthTokenManager,
                WebClient.builder()
                        .baseUrl(BASE_URL)
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build());
    }

    // Visible for testing: allows injecting a WebClient stubbed against a fake exchange function.
    TtsService(String apiKey, String defaultVoice, String cacheDir, int timeoutSeconds, boolean cacheOnly,
               String language, XaiVoiceCatalogService voiceCatalog, XaiOAuthTokenManager oauthTokenManager,
               WebClient webClient) {
        this.apiKey = apiKey;
        this.defaultVoice = defaultVoice == null || defaultVoice.isBlank() ? "orion" : defaultVoice;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheOnly = cacheOnly;
        this.language = language == null || language.isBlank() ? "en" : language;
        this.voiceCatalog = voiceCatalog;
        this.oauthTokenManager = oauthTokenManager;
        this.webClient = webClient;
        this.cachePath = Path.of(cacheDir);
        try {
            Files.createDirectories(this.cachePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create TTS cache directory: " + this.cachePath, e);
        }
        log.info("TTS service initialized (xAI), cache directory: {}", cachePath.toAbsolutePath());
    }

    public boolean isConfigured() {
        boolean hasOAuth = oauthTokenManager != null && oauthTokenManager.isConfigured();
        return hasOAuth || (apiKey != null && !apiKey.isBlank());
    }

    public boolean isCacheOnly() {
        return cacheOnly;
    }

    public List<Map<String, String>> listVoices() {
        return voiceCatalog.getVoices().stream()
                .sorted(Comparator.comparing(XaiVoice::id))
                .map(voice -> {
                    Map<String, String> mapped = new LinkedHashMap<>();
                    mapped.put("id", voice.id());
                    mapped.put("gender", voice.gender() != null ? voice.gender() : "");
                    mapped.put("description", voice.description() != null ? voice.description() : "");
                    return mapped;
                })
                .toList();
    }

    public String currentProvider() {
        return PROVIDER_XAI;
    }

    public String defaultVoice() {
        return defaultVoice;
    }

    public String resolveVoice(String requestedVoice) {
        if (isServedByCurrentProvider(requestedVoice)) {
            return requestedVoice.trim().toLowerCase(Locale.ROOT);
        }
        return defaultVoice;
    }

    /**
     * Resolve a voice chosen by book analysis. Only ids the current TTS provider
     * still serves are kept; anything else falls back to the default.
     */
    public String resolveAnalyzedVoice(String requestedVoice) {
        if (isServedByCurrentProvider(requestedVoice)) {
            return requestedVoice.trim().toLowerCase(Locale.ROOT);
        }
        log.info("event=tts_voice_rejected_not_current_provider requested={} currentProvider={} using={}",
                requestedVoice, currentProvider(), defaultVoice);
        return defaultVoice;
    }

    public String resolvePlaybackVoice(String requestedVoice) {
        return resolvePlaybackVoice(requestedVoice, null, null);
    }

    /**
     * Voice to send to the current TTS provider. A requested id from a previous
     * provider is ignored rather than mapped onto a different roster.
     */
    public String resolvePlaybackVoice(String requestedVoice, String savedVoice, String savedProvider) {
        if (isServedByCurrentProvider(requestedVoice)) {
            return requestedVoice.trim().toLowerCase(Locale.ROOT);
        }
        if (isCompatibleWithCurrentProvider(savedVoice, savedProvider)) {
            return savedVoice.trim().toLowerCase(Locale.ROOT);
        }
        return defaultVoice;
    }

    /**
     * True when the current TTS provider still offers this voice id.
     */
    public boolean isServedByCurrentProvider(String voice) {
        if (voice == null || voice.isBlank()) {
            return false;
        }
        return isCatalogVoice(voice.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * True when the saved pick can still be used: it was chosen for the provider
     * that currently serves TTS, and that provider still offers the voice id.
     * Rows without {@code tts_voice_provider} infer from catalog membership.
     */
    public boolean isCompatibleWithCurrentProvider(String voice, String storedProvider) {
        if (!isServedByCurrentProvider(voice)) {
            return false;
        }
        if (storedProvider == null || storedProvider.isBlank()) {
            return true;
        }
        return currentProvider().equalsIgnoreCase(storedProvider.trim());
    }

    public double clampSpeed(double speed) {
        if (speed <= 0) {
            return 1.0;
        }
        return Math.min(MAX_SPEED, Math.max(MIN_SPEED, speed));
    }

    public byte[] generateSpeech(String text, VoiceSettings settings) {
        String voice = resolveVoice(settings.voice());
        double speed = clampSpeed(settings.speed());

        String cacheKey = generateCacheKey(text, voice, speed);
        Path cachedFile = cachePath.resolve(cacheKey + ".mp3");
        byte[] cached = readAudioFile(cachedFile);
        if (cached != null) {
            log.debug("Cache hit for TTS: {}", cacheKey);
            return cached;
        }

        if (cacheOnly) {
            log.info("TTS cache-only mode enabled, skipping generation for cache miss: {}", cacheKey);
            return null;
        }

        log.info("Generating TTS for {} chars with voice={}, speed={}", text.length(), voice, speed);
        byte[] audio = synthesize(text, voice, speed);
        writeAudioFile(cachedFile, audio);
        return audio;
    }

    public byte[] generateSpeechForParagraph(String bookKey, int chapterIndex, int paragraphIndex,
                                              String text, VoiceSettings settings) {
        String textPreview = truncateForLog(text);
        byte[] cached = getCachedSpeechForParagraph(bookKey, chapterIndex, paragraphIndex, settings.voice());
        if (cached != null) {
            log.info("TTS cache HIT: book={}, chapter={}, paragraph={}, text=\"{}\"",
                    bookKey, chapterIndex, paragraphIndex, textPreview);
            return cached;
        }

        if (cacheOnly) {
            log.info("TTS cache-only mode enabled, skipping generation for cache miss: book={}, chapter={}, paragraph={}",
                    bookKey, chapterIndex, paragraphIndex);
            return null;
        }

        String voice = resolveVoice(settings.voice());
        double speed = clampSpeed(settings.speed());
        Path cachedFile = resolveParagraphCacheFile(bookKey, chapterIndex, paragraphIndex, voice);
        try {
            Files.createDirectories(cachedFile.getParent());
        } catch (IOException e) {
            log.warn("Failed to create book cache directory", e);
        }

        log.info("TTS cache MISS - calling {}: book={}, chapter={}, paragraph={}, text=\"{}\"",
                currentProvider(), bookKey, chapterIndex, paragraphIndex, textPreview);

        byte[] audio = synthesize(text, voice, speed);
        writeAudioFile(cachedFile, audio);
        return audio;
    }

    /**
     * Return cached paragraph audio if any voice already has a file for this
     * paragraph. Provider switches must not regenerate (or spend tokens) when
     * audio is already on disk.
     */
    public byte[] getCachedSpeechForParagraph(String bookKey, int chapterIndex, int paragraphIndex, String requestedVoice) {
        Set<String> preferred = preferredCacheVoices(requestedVoice);
        for (String voice : preferred) {
            byte[] audio = readAudioFile(resolveParagraphCacheFile(bookKey, chapterIndex, paragraphIndex, voice));
            if (isUsableAudio(audio)) {
                return audio;
            }
        }
        return findAnyCachedParagraphAudio(bookKey, chapterIndex, paragraphIndex, preferred);
    }

    public int estimateCost(int characterCount) {
        // Rough display estimate; xAI TTS is billed against SuperGrok OAuth or API usage.
        return (int) Math.ceil(characterCount * 0.0015);
    }

    Map<String, Object> buildSpeechRequest(String text, String voiceId, double speed) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("text", text);
        requestBody.put("voice_id", voiceId);
        requestBody.put("language", language);
        requestBody.put("speed", speed);
        requestBody.put("output_format", Map.of(
                "codec", "mp3",
                "sample_rate", 24000,
                "bit_rate", 128000
        ));
        return requestBody;
    }

    private byte[] synthesize(String text, String voice, double speed) {
        Optional<String> oauthToken = oauthTokenManager != null
                ? oauthTokenManager.getAccessToken()
                : Optional.empty();
        boolean usingOAuth = oauthToken.isPresent();
        String bearerToken = oauthToken.orElse(apiKey);

        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalStateException(
                    "xAI TTS unavailable: OAuth token unavailable and no API key configured as fallback");
        }

        Map<String, Object> requestBody = buildSpeechRequest(text, voice, speed);
        log.info("event=xai_tts auth_source={} voice={}", usingOAuth ? "oauth" : "api_key", voice);

        try {
            return callTts(requestBody, bearerToken);
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean oauthRejected = usingOAuth && (status == 401 || status == 402 || status == 403);
            if (oauthRejected && apiKey != null && !apiKey.isBlank()) {
                log.warn("event=xai_tts_oauth_rejected status={} retrying_with=api_key", status);
                oauthTokenManager.invalidate();
                return callTts(requestBody, apiKey);
            }
            throw e;
        }
    }

    private byte[] callTts(Map<String, Object> requestBody, String bearerToken) {
        return webClient.post()
                .uri("/tts")
                .header("Authorization", "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(timeoutSeconds));
    }

    private boolean isCatalogVoice(String id) {
        return voiceCatalog.getVoices().stream().anyMatch(voice -> voice.id().equals(id));
    }

    private Set<String> preferredCacheVoices(String requestedVoice) {
        Set<String> preferred = new LinkedHashSet<>();
        String safeVoice = sanitizeCacheVoice(requestedVoice);
        if (safeVoice != null) {
            preferred.add(safeVoice);
            preferred.add(safeVoice.toLowerCase(Locale.ROOT));
        }
        if (defaultVoice != null && !defaultVoice.isBlank()) {
            preferred.add(defaultVoice);
        }
        return preferred;
    }

    private byte[] findAnyCachedParagraphAudio(String bookKey, int chapterIndex, int paragraphIndex,
                                               Set<String> alreadyTried) {
        Path audioRoot = cachePath.resolve(bookKey).resolve("audio");
        if (!Files.isDirectory(audioRoot)) {
            return null;
        }
        try (var voiceDirs = Files.list(audioRoot)) {
            List<Path> directories = voiceDirs
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path voiceDir : directories) {
                String voiceName = voiceDir.getFileName().toString();
                if (alreadyTried.contains(voiceName)) {
                    continue;
                }
                Path cachedFile = voiceDir.resolve("chapters")
                        .resolve(String.valueOf(chapterIndex))
                        .resolve(paragraphIndex + ".mp3");
                byte[] audio = readAudioFile(cachedFile);
                if (isUsableAudio(audio)) {
                    log.info("event=tts_cache_hit_any_voice book={} chapter={} paragraph={} voice={}",
                            bookKey, chapterIndex, paragraphIndex, voiceName);
                    return audio;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan TTS cache for existing paragraph audio", e);
        }
        return null;
    }

    private boolean isUsableAudio(byte[] audio) {
        return audio != null && audio.length > 0;
    }

    private String generateCacheKey(String text, String voice, double speed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = text + "|" + voice + "|" + speed;
            byte[] hash = md.digest(input.getBytes());
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    private String truncateForLog(String text) {
        if (text == null) {
            return "";
        }
        String[] words = text.split("\\s+", 9);
        if (words.length <= 8) {
            return text.length() <= 50 ? text : text.substring(0, 50) + "...";
        }
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                preview.append(" ");
            }
            preview.append(words[i]);
        }
        return preview + "...";
    }

    private byte[] readAudioFile(Path cachedFile) {
        if (!Files.exists(cachedFile)) {
            return null;
        }
        try {
            return Files.readAllBytes(cachedFile);
        } catch (IOException e) {
            log.warn("Failed to read cached file", e);
            return null;
        }
    }

    private void writeAudioFile(Path cachedFile, byte[] audio) {
        if (audio == null || audio.length == 0) {
            return;
        }
        try {
            Files.createDirectories(cachedFile.getParent());
            Files.write(cachedFile, audio);
        } catch (IOException e) {
            log.warn("Failed to cache audio file", e);
        }
    }

    private Path resolveParagraphCacheFile(String bookKey, int chapterIndex, int paragraphIndex, String voice) {
        String safeVoice = sanitizeCacheVoice(voice);
        if (safeVoice == null) {
            safeVoice = defaultVoice;
        }
        return cachePath.resolve(bookKey)
                .resolve("audio")
                .resolve(safeVoice)
                .resolve("chapters")
                .resolve(String.valueOf(chapterIndex))
                .resolve(paragraphIndex + ".mp3");
    }

    /**
     * Voices become path segments. Reject anything that can escape the cache root.
     */
    private String sanitizeCacheVoice(String voice) {
        if (voice == null || voice.isBlank()) {
            return null;
        }
        String trimmed = voice.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            return null;
        }
        if (!trimmed.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        return trimmed;
    }
}
