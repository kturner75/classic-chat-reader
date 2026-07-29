package com.classicchatreader.service.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaiRealtimeSessionServiceTest {

    @Test
    void mintSession_noOAuthManager_usesApiKeyAndParsesFlatShape() {
        List<String> authHeaders = new ArrayList<>();
        XaiRealtimeSessionService service = service(
                "api-key", null, recordingWebClient(authHeaders, "{\"value\":\"secret-1\",\"expires_at\":1234}"));

        XaiRealtimeSessionService.RealtimeSession session = service.mintSession();

        assertEquals("secret-1", session.clientSecret());
        assertEquals(1234L, session.expiresAtEpochSeconds());
        assertEquals("grok-voice-think-fast-2.0", session.model());
        assertEquals(List.of("Bearer api-key"), authHeaders);
    }

    @Test
    void mintSession_parsesNestedClientSecretShape() {
        XaiRealtimeSessionService service = service(
                "api-key", null,
                recordingWebClient(new ArrayList<>(), "{\"client_secret\":{\"value\":\"secret-2\",\"expires_at\":99}}"));

        XaiRealtimeSessionService.RealtimeSession session = service.mintSession();

        assertEquals("secret-2", session.clientSecret());
        assertEquals(99L, session.expiresAtEpochSeconds());
    }

    @Test
    void mintSession_missingExpiry_defaultsToNowPlusTtl() {
        long before = java.time.Instant.now().getEpochSecond();
        XaiRealtimeSessionService service = service(
                "api-key", null, recordingWebClient(new ArrayList<>(), "{\"value\":\"secret-3\"}"));

        XaiRealtimeSessionService.RealtimeSession session = service.mintSession();

        assertTrue(session.expiresAtEpochSeconds() >= before + 1800);
    }

    @Test
    void mintSession_oauthConfigured_prefersOAuthToken() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager(
                "refresh-token", true, null, oauthWebClient(tokenResponse(3600)));
        XaiRealtimeSessionService service = service(
                "api-key", oauthManager, recordingWebClient(authHeaders, "{\"value\":\"secret\"}"));

        service.mintSession();

        assertEquals(List.of("Bearer oauth-access-token"), authHeaders);
    }

    @Test
    void mintSession_oauthRejectedWith403_invalidatesAndRetriesWithApiKey() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager(
                "refresh-token", true, null, oauthWebClient(tokenResponse(3600)));
        XaiRealtimeSessionService service = service(
                "api-key", oauthManager,
                failThenSuccessWebClient(authHeaders, HttpStatus.FORBIDDEN, "{\"value\":\"recovered\"}"));

        XaiRealtimeSessionService.RealtimeSession session = service.mintSession();

        assertEquals("recovered", session.clientSecret());
        assertEquals(List.of("Bearer oauth-access-token", "Bearer api-key"), authHeaders);
        assertTrue(service.isAvailable());
    }

    @Test
    void mintSession_noCredentials_throwsWithoutCalling() {
        List<String> authHeaders = new ArrayList<>();
        XaiRealtimeSessionService service = service(
                "", null, recordingWebClient(authHeaders, "{\"value\":\"unused\"}"));

        assertThrows(LlmProviderException.class, service::mintSession);
        assertTrue(authHeaders.isEmpty());
    }

    @Test
    void isAvailable_afterHardMintFailure_entersCooldown() {
        XaiRealtimeSessionService service = service(
                "api-key", null, alwaysFailingWebClient(HttpStatus.PAYMENT_REQUIRED));

        assertTrue(service.isAvailable());
        assertThrows(LlmProviderException.class, service::mintSession);
        assertFalse(service.isAvailable());
    }

    @Test
    void mintSession_invalidResponseBody_throws() {
        XaiRealtimeSessionService service = service(
                "api-key", null, recordingWebClient(new ArrayList<>(), "{\"unexpected\":true}"));

        assertThrows(LlmProviderException.class, service::mintSession);
    }

    private XaiRealtimeSessionService service(String apiKey, XaiOAuthTokenManager oauthManager, WebClient webClient) {
        return new XaiRealtimeSessionService(apiKey, "grok-voice-think-fast-2.0", 1800, 10, oauthManager, webClient);
    }

    private String tokenResponse(int expiresInSeconds) {
        return """
                {"access_token":"oauth-access-token","expires_in":%d,"token_type":"Bearer"}
                """.formatted(expiresInSeconds);
    }

    private WebClient recordingWebClient(List<String> authHeaders, String jsonBody) {
        ExchangeFunction exchangeFunction = request -> {
            captureAuthHeader(request, authHeaders);
            return Mono.just(okResponse(jsonBody));
        };
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private WebClient failThenSuccessWebClient(List<String> authHeaders, HttpStatus failureStatus, String successBody) {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchangeFunction = request -> {
            captureAuthHeader(request, authHeaders);
            if (calls.getAndIncrement() == 0) {
                return Mono.just(ClientResponse.create(failureStatus)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"rejected\"}")
                        .build());
            }
            return Mono.just(okResponse(successBody));
        };
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private WebClient alwaysFailingWebClient(HttpStatus status) {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":\"denied\"}")
                .build());
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private WebClient oauthWebClient(String jsonBody) {
        ExchangeFunction exchangeFunction = request -> Mono.just(okResponse(jsonBody));
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private void captureAuthHeader(ClientRequest request, List<String> authHeaders) {
        authHeaders.add(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    private ClientResponse okResponse(String jsonBody) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(jsonBody)
                .build();
    }
}
