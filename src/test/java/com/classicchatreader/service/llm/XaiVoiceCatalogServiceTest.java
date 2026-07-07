package com.classicchatreader.service.llm;

import com.classicchatreader.service.llm.XaiVoiceCatalogService.XaiVoice;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class XaiVoiceCatalogServiceTest {

    @Test
    void getVoices_parsesDocumentedShape_voiceIdWinsOverDisplayName() {
        List<String> authHeaders = new ArrayList<>();
        XaiVoiceCatalogService service = service("api-key", recordingWebClient(authHeaders, """
                {"voices":[
                  {"voice_id":"ara","name":"Ara","language":"en"},
                  {"voice_id":"zagan","name":"Zagan (Deep Narrator)","language":"en"}
                ]}
                """));

        List<XaiVoice> voices = service.getVoices();

        assertEquals(List.of(
                new XaiVoice("ara", null, null),
                new XaiVoice("zagan", null, null)), voices);
        assertEquals(List.of("Bearer api-key"), authHeaders);
    }

    @Test
    void getVoices_parsesOptionalGenderAndDescriptionFields() {
        XaiVoiceCatalogService service = service("api-key", recordingWebClient(new ArrayList<>(), """
                {"voices":[
                  {"id":"Atlas","gender":"male","description":"Grounded and reassuring"},
                  {"id":"luna","labels":{"gender":"female"},"preview_text":"Soft and dreamy"}
                ]}
                """));

        List<XaiVoice> voices = service.getVoices();

        assertEquals(List.of(
                new XaiVoice("atlas", "male", "Grounded and reassuring"),
                new XaiVoice("luna", "female", "Soft and dreamy")), voices);
    }

    @Test
    void getVoices_parsesBareArrayAndToleratesMissingFields() {
        XaiVoiceCatalogService service = service("api-key", recordingWebClient(new ArrayList<>(), """
                [
                  {"name":"rex"},
                  {"description":"no id, skipped"},
                  {"voice":"eve","tone":"bright"}
                ]
                """));

        List<XaiVoice> voices = service.getVoices();

        assertEquals(2, voices.size());
        assertEquals("rex", voices.get(0).id());
        assertNull(voices.get(0).gender());
        assertNull(voices.get(0).description());
        assertEquals(new XaiVoice("eve", null, "bright"), voices.get(1));
    }

    @Test
    void getVoices_httpFailure_returnsFallbackWithoutThrowing() {
        XaiVoiceCatalogService service = service("api-key", failingWebClient(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(XaiVoiceCatalogService.FALLBACK_VOICES, service.getVoices());
    }

    @Test
    void getVoices_emptyRoster_returnsFallback() {
        XaiVoiceCatalogService service = service("api-key",
                recordingWebClient(new ArrayList<>(), "{\"voices\":[]}"));

        assertEquals(XaiVoiceCatalogService.FALLBACK_VOICES, service.getVoices());
    }

    @Test
    void getVoices_noApiKey_returnsFallbackWithoutCalling() {
        AtomicInteger calls = new AtomicInteger();
        XaiVoiceCatalogService service = service("", countingWebClient(calls, "{\"voices\":[]}"));

        assertEquals(XaiVoiceCatalogService.FALLBACK_VOICES, service.getVoices());
        assertEquals(0, calls.get());
    }

    @Test
    void getVoices_cachesSuccessfulFetch() {
        AtomicInteger calls = new AtomicInteger();
        XaiVoiceCatalogService service = service("api-key",
                countingWebClient(calls, "{\"voices\":[{\"id\":\"ara\"}]}"));

        service.getVoices();
        List<XaiVoice> second = service.getVoices();

        assertEquals(1, calls.get());
        assertEquals("ara", second.get(0).id());
    }

    @Test
    void getVoices_afterFailure_cooldownSuppressesImmediateRetry() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchangeFunction = request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\":\"down\"}")
                    .build());
        };
        XaiVoiceCatalogService service = service("api-key",
                WebClient.builder().exchangeFunction(exchangeFunction).build());

        assertEquals(XaiVoiceCatalogService.FALLBACK_VOICES, service.getVoices());
        assertEquals(XaiVoiceCatalogService.FALLBACK_VOICES, service.getVoices());
        assertEquals(1, calls.get());
    }

    @Test
    void getVoices_oauthConfigured_prefersOAuthToken() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager(
                "refresh-token", true, null, oauthWebClient(tokenResponse(3600)));
        XaiVoiceCatalogService service = service("api-key", oauthManager,
                recordingWebClient(authHeaders, "{\"voices\":[{\"id\":\"ara\"}]}"));

        service.getVoices();

        assertEquals(List.of("Bearer oauth-access-token"), authHeaders);
    }

    @Test
    void getVoices_oauthRejectedWith403_retriesWithApiKey() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager(
                "refresh-token", true, null, oauthWebClient(tokenResponse(3600)));
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchangeFunction = request -> {
            authHeaders.add(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            if (calls.getAndIncrement() == 0) {
                return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"rejected\"}")
                        .build());
            }
            return Mono.just(okResponse("{\"voices\":[{\"id\":\"luna\"}]}"));
        };
        XaiVoiceCatalogService service = service("api-key", oauthManager,
                WebClient.builder().exchangeFunction(exchangeFunction).build());

        List<XaiVoice> voices = service.getVoices();

        assertEquals("luna", voices.get(0).id());
        assertEquals(List.of("Bearer oauth-access-token", "Bearer api-key"), authHeaders);
    }

    @Test
    void getVoices_oauthOnlyNoApiKey_fetchesWithOAuth() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager(
                "refresh-token", true, null, oauthWebClient(tokenResponse(3600)));
        XaiVoiceCatalogService service = service("", oauthManager,
                recordingWebClient(authHeaders, "{\"voices\":[{\"id\":\"atlas\"}]}"));

        List<XaiVoice> voices = service.getVoices();

        assertEquals("atlas", voices.get(0).id());
        assertEquals(List.of("Bearer oauth-access-token"), authHeaders);
    }

    private XaiVoiceCatalogService service(String apiKey, WebClient webClient) {
        return service(apiKey, null, webClient);
    }

    private XaiVoiceCatalogService service(String apiKey, XaiOAuthTokenManager oauthManager, WebClient webClient) {
        return new XaiVoiceCatalogService(apiKey, "https://api.x.ai/v1/tts/voices", 10, 1440,
                oauthManager, webClient);
    }

    private String tokenResponse(int expiresInSeconds) {
        return """
                {"access_token":"oauth-access-token","expires_in":%d,"token_type":"Bearer"}
                """.formatted(expiresInSeconds);
    }

    private WebClient oauthWebClient(String jsonBody) {
        ExchangeFunction exchangeFunction = request -> Mono.just(okResponse(jsonBody));
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private WebClient recordingWebClient(List<String> authHeaders, String jsonBody) {
        ExchangeFunction exchangeFunction = request -> {
            authHeaders.add(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            return Mono.just(okResponse(jsonBody));
        };
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private WebClient countingWebClient(AtomicInteger calls, String jsonBody) {
        ExchangeFunction exchangeFunction = request -> {
            calls.incrementAndGet();
            return Mono.just(okResponse(jsonBody));
        };
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private WebClient failingWebClient(HttpStatus status) {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":\"boom\"}")
                .build());
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private ClientResponse okResponse(String jsonBody) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(jsonBody)
                .build();
    }
}
