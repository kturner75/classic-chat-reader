package com.classicchatreader.service;

import com.classicchatreader.model.VoiceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    // Available voices with descriptions for LLM analysis (curated based on listening tests)
    public static final List<Map<String, String>> AVAILABLE_VOICES = List.of(
        Map.of("id", "ash", "gender", "masculine", "accent", "american", "description", "Deep masculine voice, clear and authoritative"),
        Map.of("id", "ballad", "gender", "masculine", "accent", "british", "description", "British masculine voice, higher-pitched, smooth and melodic"),
        Map.of("id", "fable", "gender", "feminine", "accent", "british", "description", "British feminine voice, warm and expressive, excellent for classic literature and romance"),
        Map.of("id", "onyx", "gender", "masculine", "accent", "american", "description", "Deep masculine voice, commanding and dramatic, ideal for horror, thriller, and epic tales"),
        Map.of("id", "sage", "gender", "feminine", "accent", "american", "description", "Feminine voice, high-pitched but very expressive, excellent for dialog-heavy stories"),
        Map.of("id", "shimmer", "gender", "feminine", "accent", "american", "description", "Feminine voice, level pitch, gentle and steady"),
        Map.of("id", "verse", "gender", "masculine", "accent", "american", "description", "Masculine voice, upbeat and clear, typical professional audiobook narrator style")
    );

    @Value("${tts.openai.api-key}")
    private String apiKey;

    @Value("${tts.openai.model}")
    private String model;

    @Value("${tts.openai.default-voice}")
    private String defaultVoice;

    @Value("${tts.cache-dir}")
    private String cacheDir;

    @Value("${tts.openai.timeout-seconds:45}")
    private int timeoutSeconds;

    @Value("${tts.cache-only:false}")
    private boolean cacheOnly;

    private WebClient webClient;
    private Path cachePath;

    @PostConstruct
    public void init() throws IOException {
        this.webClient = WebClient.builder()
            .baseUrl("https://api.openai.com/v1")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();

        this.cachePath = Path.of(cacheDir);
        Files.createDirectories(cachePath);
        log.info("TTS service initialized, cache directory: {}", cachePath.toAbsolutePath());
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isCacheOnly() {
        return cacheOnly;
    }

    public String resolveVoice(String requestedVoice) {
        return (requestedVoice != null && !requestedVoice.isBlank()) ? requestedVoice : defaultVoice;
    }

    public byte[] generateSpeech(String text, VoiceSettings settings) {
        String voice = settings.voice() != null ? settings.voice() : defaultVoice;
        double speed = settings.speed() > 0 ? settings.speed() : 1.0;
        String instructions = settings.instructions();

        // Check cache first
        String cacheKey = generateCacheKey(text, voice, speed, instructions);
        Path cachedFile = cachePath.resolve(cacheKey + ".mp3");

        if (Files.exists(cachedFile)) {
            log.debug("Cache hit for TTS: {}", cacheKey);
            try {
                return Files.readAllBytes(cachedFile);
            } catch (IOException e) {
                log.warn("Failed to read cached file, regenerating", e);
            }
        }

        if (cacheOnly) {
            log.info("TTS cache-only mode enabled, skipping generation for cache miss: {}", cacheKey);
            return null;
        }

        // Generate via OpenAI API
        log.info("Generating TTS for {} chars with voice={}, speed={}", text.length(), voice, speed);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", text);
        requestBody.put("voice", voice);
        requestBody.put("speed", speed);
        requestBody.put("response_format", "mp3");

        if (instructions != null && !instructions.isBlank()) {
            requestBody.put("instructions", instructions);
        }

        byte[] audio = webClient.post()
            .uri("/audio/speech")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(byte[].class)
            .block(Duration.ofSeconds(timeoutSeconds));

        // Cache the result
        if (audio != null && audio.length > 0) {
            try {
                Files.write(cachedFile, audio);
                log.debug("Cached TTS audio: {}", cachedFile);
            } catch (IOException e) {
                log.warn("Failed to cache audio file", e);
            }
        }

        return audio;
    }

    public byte[] generateSpeechForParagraph(String bookKey, int chapterIndex, int paragraphIndex,
                                              String text, VoiceSettings settings) {
        String voice = resolveVoice(settings.voice());
        double speed = settings.speed() > 0 ? settings.speed() : 1.0;
        String instructions = settings.instructions();
        Path cachedFile = resolveParagraphCacheFile(bookKey, chapterIndex, paragraphIndex, voice);
        String textPreview = truncateForLog(text);
        if (Files.exists(cachedFile)) {
            log.info("TTS cache HIT: book={}, chapter={}, paragraph={}, text=\"{}\"",
                     bookKey, chapterIndex, paragraphIndex, textPreview);
            try {
                return Files.readAllBytes(cachedFile);
            } catch (IOException e) {
                log.warn("Failed to read cached file", e);
            }
        }

        if (cacheOnly) {
            log.info("TTS cache-only mode enabled, skipping generation for cache miss: book={}, chapter={}, paragraph={}",
                     bookKey, chapterIndex, paragraphIndex);
            return null;
        }

        try {
            Files.createDirectories(cachedFile.getParent());
        } catch (IOException e) {
            log.warn("Failed to create book cache directory", e);
        }

        // Generate - this means we're calling OpenAI API
        log.info("TTS cache MISS - calling OpenAI: book={}, chapter={}, paragraph={}, text=\"{}\"",
                 bookKey, chapterIndex, paragraphIndex, textPreview);

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", text);
        requestBody.put("voice", voice);
        requestBody.put("speed", speed);
        requestBody.put("response_format", "mp3");

        if (instructions != null && !instructions.isBlank()) {
            requestBody.put("instructions", instructions);
        }

        byte[] audio = webClient.post()
            .uri("/audio/speech")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(byte[].class)
            .block(Duration.ofSeconds(timeoutSeconds));

        // Cache
        if (audio != null && audio.length > 0) {
            try {
                Files.write(cachedFile, audio);
            } catch (IOException e) {
                log.warn("Failed to cache audio", e);
            }
        }

        return audio;
    }

    public byte[] getCachedSpeechForParagraph(String bookKey, int chapterIndex, int paragraphIndex, String requestedVoice) {
        String voice = resolveVoice(requestedVoice);
        Path cachedFile = resolveParagraphCacheFile(bookKey, chapterIndex, paragraphIndex, voice);
        if (!Files.exists(cachedFile)) {
            return null;
        }
        try {
            return Files.readAllBytes(cachedFile);
        } catch (IOException e) {
            log.warn("Failed to read cached paragraph audio", e);
            return null;
        }
    }

    public int estimateCost(int characterCount) {
        // $15 per 1M characters = $0.000015 per character
        // Return cost in cents for easier display
        return (int) Math.ceil(characterCount * 0.0015);
    }

    private String generateCacheKey(String text, String voice, double speed, String instructions) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = text + "|" + voice + "|" + speed + "|" + (instructions != null ? instructions : "");
            byte[] hash = md.digest(input.getBytes());
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    private String truncateForLog(String text) {
        if (text == null) return "";
        // Get first ~50 chars or first 8 words, whichever is shorter
        String[] words = text.split("\\s+", 9);
        if (words.length <= 8) {
            return text.length() <= 50 ? text : text.substring(0, 50) + "...";
        }
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) preview.append(" ");
            preview.append(words[i]);
        }
        return preview + "...";
    }

    private Path resolveParagraphCacheFile(String bookKey, int chapterIndex, int paragraphIndex, String voice) {
        return cachePath.resolve(bookKey)
                .resolve("audio")
                .resolve(voice)
                .resolve("chapters")
                .resolve(String.valueOf(chapterIndex))
                .resolve(paragraphIndex + ".mp3");
    }
}
