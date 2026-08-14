package com.classicchatreader.service;

import com.classicchatreader.model.VoiceSettings;
import com.classicchatreader.service.llm.XaiOAuthTokenManager;
import com.classicchatreader.service.llm.XaiVoiceCatalogService;
import com.classicchatreader.service.llm.XaiVoiceCatalogService.XaiVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    @TempDir
    Path cacheDir;

    @Test
    void resolveVoice_blankUsesDefault() {
        TtsService service = service("api-key", null);

        assertEquals("orion", service.resolveVoice(null));
        assertEquals("orion", service.resolveVoice("  "));
    }

    @Test
    void resolveVoice_unknownIdsFallBackToDefaultWithoutMapping() {
        TtsService service = service("api-key", null);

        assertEquals("orion", service.resolveVoice("fable"));
        assertEquals("orion", service.resolveVoice("ONYX"));
        assertEquals("orion", service.resolveVoice("ash"));
        assertEquals("orion", service.resolveVoice("ballad"));
        assertEquals("orion", service.resolveVoice("sage"));
        assertEquals("orion", service.resolveVoice("not-a-voice"));
        assertFalse(service.isServedByCurrentProvider("fable"));
        assertFalse(service.isServedByCurrentProvider("alloy"));
        assertTrue(service.isServedByCurrentProvider("eve"));
        assertFalse(service.isServedByCurrentProvider(null));
    }

    @Test
    void resolveAnalyzedVoice_keepsCurrentCatalogAndRejectsOthers() {
        TtsService service = service("api-key", null);

        assertEquals("orion", service.resolveAnalyzedVoice("fable"));
        assertEquals("orion", service.resolveAnalyzedVoice("ballad"));
        assertEquals("eve", service.resolveAnalyzedVoice("eve"));
        assertEquals("orion", service.resolveAnalyzedVoice("not-a-voice"));
    }

    @Test
    void isCompatibleWithCurrentProvider_requiresCurrentProviderAndCatalogVoice() {
        TtsService service = service("api-key", null);

        assertEquals("xai", service.currentProvider());
        assertTrue(service.isServedByCurrentProvider("orion"));
        assertFalse(service.isServedByCurrentProvider("fable"));
        assertFalse(service.isServedByCurrentProvider("ballad"));

        assertTrue(service.isCompatibleWithCurrentProvider("orion", "xai"));
        assertFalse(service.isCompatibleWithCurrentProvider("ara", "openai"));
        assertFalse(service.isCompatibleWithCurrentProvider("orion", "other-provider"));
        assertFalse(service.isCompatibleWithCurrentProvider("ballad", null));
        assertTrue(service.isCompatibleWithCurrentProvider("eve", null));
        assertFalse(service.isCompatibleWithCurrentProvider(null, "xai"));
    }

    @Test
    void resolvePlaybackVoice_ignoresVoicesNotServedByCurrentProvider() {
        TtsService service = service("api-key", null);

        assertEquals("orion", service.resolvePlaybackVoice("fable", null, null));
        assertEquals("orion", service.resolvePlaybackVoice("fable", "ballad", "openai"));
        assertEquals("eve", service.resolvePlaybackVoice("fable", "eve", "xai"));
        assertEquals("atlas", service.resolvePlaybackVoice("atlas", "eve", "xai"));
    }

    @Test
    void resolveVoice_keepsCatalogIdsAndFallsBackForUnknown() {
        TtsService service = service("api-key", null);

        assertEquals("eve", service.resolveVoice("eve"));
        assertEquals("atlas", service.resolveVoice("Atlas"));
        assertEquals("orion", service.resolveVoice("not-a-voice"));
    }

    @Test
    void clampSpeed_enforcesXaiRange() {
        TtsService service = service("api-key", null);

        assertEquals(1.0, service.clampSpeed(0));
        assertEquals(0.7, service.clampSpeed(0.5), 0.0001);
        assertEquals(1.5, service.clampSpeed(2.0), 0.0001);
        assertEquals(0.95, service.clampSpeed(0.95), 0.0001);
    }

    @Test
    void listVoices_sortsById() {
        TtsService service = service("api-key", null);

        List<Map<String, String>> voices = service.listVoices();

        assertEquals(List.of("ara", "atlas", "eve", "leo", "orion", "rex", "sal"),
                voices.stream().map(v -> v.get("id")).toList());
        assertEquals("female", voices.get(0).get("gender"));
        assertEquals("Warm, friendly and conversational", voices.get(0).get("description"));
    }

    @Test
    void isConfigured_requiresOauthOrApiKey() {
        assertFalse(service("", null).isConfigured());
        assertTrue(service("api-key", null).isConfigured());
        assertTrue(service("", oauthManager()).isConfigured());
    }

    @Test
    void buildSpeechRequest_usesVoiceIdLanguageAndClampedSpeed() {
        TtsService service = service("api-key", null);

        Map<String, Object> body = service.buildSpeechRequest("Hello there.", "ara", 1.1);

        assertEquals("Hello there.", body.get("text"));
        assertEquals("ara", body.get("voice_id"));
        assertEquals("en", body.get("language"));
        assertEquals(1.1, body.get("speed"));
        assertEquals(Map.of("codec", "mp3", "sample_rate", 24000, "bit_rate", 128000),
                body.get("output_format"));
    }

    @Test
    void generateSpeech_postsToXaiTtsAndCaches() {
        List<String> authHeaders = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        TtsService service = service("api-key", null, recordingWebClient(authHeaders, paths, "fake-mp3"));

        byte[] audio = service.generateSpeech("Once upon a time.", new VoiceSettings("fable", 1.0, "warm", null));

        assertEquals("fake-mp3", new String(audio, StandardCharsets.UTF_8));
        assertEquals(List.of("Bearer api-key"), authHeaders);
        assertEquals(List.of("/tts"), paths);

        authHeaders.clear();
        byte[] cached = service.generateSpeech("Once upon a time.", new VoiceSettings("fable", 1.0, "ignored", null));
        assertEquals("fake-mp3", new String(cached, StandardCharsets.UTF_8));
        assertTrue(authHeaders.isEmpty());
    }

    @Test
    void generateSpeech_oauthRejectedRetriesWithApiKey() {
        List<String> authHeaders = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchangeFunction = request -> {
            authHeaders.add(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            if (calls.getAndIncrement() == 0) {
                return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"rejected\"}")
                        .build());
            }
            return Mono.just(audioResponse("ok-audio"));
        };
        TtsService service = service("api-key", oauthManager(),
                WebClient.builder().exchangeFunction(exchangeFunction).build());

        byte[] audio = service.generateSpeech("Hello", VoiceSettings.defaults());

        assertEquals("ok-audio", new String(audio, StandardCharsets.UTF_8));
        assertEquals(List.of("Bearer oauth-access-token", "Bearer api-key"), authHeaders);
    }

    @Test
    void getCachedSpeechForParagraph_fallsBackToAnyCachedVoice() throws Exception {
        TtsService service = service("api-key", null);
        Path legacyFile = cacheDir.resolve("book-one/audio/fable/chapters/0/1.mp3");
        java.nio.file.Files.createDirectories(legacyFile.getParent());
        java.nio.file.Files.writeString(legacyFile, "legacy-audio");

        byte[] byLegacyName = service.getCachedSpeechForParagraph("book-one", 0, 1, "fable");
        byte[] byCurrentVoice = service.getCachedSpeechForParagraph("book-one", 0, 1, "orion");

        assertEquals("legacy-audio", new String(byLegacyName, StandardCharsets.UTF_8));
        assertNull(byCurrentVoice);
    }

    @Test
    void getCachedSpeechForParagraph_doesNotUseDefaultVoiceForAnotherCatalogVoice() throws Exception {
        TtsService service = service("api-key", null);
        Path defaultFile = cacheDir.resolve("book-one/audio/orion/chapters/0/1.mp3");
        java.nio.file.Files.createDirectories(defaultFile.getParent());
        java.nio.file.Files.writeString(defaultFile, "orion-audio");

        assertNull(service.getCachedSpeechForParagraph("book-one", 0, 1, "eve"));
        assertEquals("orion-audio", new String(
                service.getCachedSpeechForParagraph("book-one", 0, 1, "orion"), StandardCharsets.UTF_8));
    }

    @Test
    void getCachedSpeechForParagraph_ignoresPathTraversalVoice() throws Exception {
        TtsService service = service("api-key", null);
        Path secret = cacheDir.resolve("secret.mp3");
        java.nio.file.Files.writeString(secret, "secret-audio");

        assertNull(service.getCachedSpeechForParagraph("book-one", 0, 1, "../secret"));
    }

    @Test
    void generateSpeechForParagraph_reusesCachedAudioForLegacyVoice() throws Exception {
        List<String> authHeaders = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        TtsService service = service("api-key", null, recordingWebClient(authHeaders, paths, "should-not-call"));
        Path legacyFile = cacheDir.resolve("book-one/audio/fable/chapters/0/1.mp3");
        java.nio.file.Files.createDirectories(legacyFile.getParent());
        java.nio.file.Files.writeString(legacyFile, "legacy-audio");

        byte[] audio = service.generateSpeechForParagraph(
                "book-one", 0, 1, "The thousand injuries of Fortunato...",
                new VoiceSettings("fable", 1.0, null, null, "openai"));

        assertEquals("legacy-audio", new String(audio, StandardCharsets.UTF_8));
        assertTrue(authHeaders.isEmpty());
        assertTrue(paths.isEmpty());
    }

    private TtsService service(String apiKey, XaiOAuthTokenManager oauth) {
        return service(apiKey, oauth, WebClient.builder()
                .exchangeFunction(request -> Mono.just(audioResponse("unused")))
                .build());
    }

    private TtsService service(String apiKey, XaiOAuthTokenManager oauth, WebClient webClient) {
        XaiVoiceCatalogService catalog = mock(XaiVoiceCatalogService.class);
        when(catalog.getVoices()).thenReturn(List.of(
                new XaiVoice("eve", "female", "Bright, energetic and expressive"),
                new XaiVoice("ara", "female", "Warm, friendly and conversational"),
                new XaiVoice("atlas", "male", "Grounded and reassuring"),
                new XaiVoice("leo", "male", "Authoritative and composed"),
                new XaiVoice("orion", "male", "Rich, cinematic, and resonant"),
                new XaiVoice("rex", "male", "Deep, calm and steady"),
                new XaiVoice("sal", "male", "Smooth and laid-back")
        ));
        return new TtsService(apiKey, "orion", cacheDir.toString(), 10, false, "en",
                catalog, oauth, webClient);
    }

    private XaiOAuthTokenManager oauthManager() {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.isConfigured()).thenReturn(true);
        when(oauth.getAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        return oauth;
    }

    private WebClient recordingWebClient(List<String> authHeaders, List<String> paths, String audio) {
        ExchangeFunction exchangeFunction = request -> {
            authHeaders.add(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            paths.add(request.url().getPath());
            return Mono.just(audioResponse(audio));
        };
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private ClientResponse audioResponse(String audio) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                .body(Flux.just(new DefaultDataBufferFactory().wrap(audio.getBytes(StandardCharsets.UTF_8))))
                .build();
    }
}
