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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaiLlmProviderTest {

    @Test
    void buildRequestBody_includesReasoningEffortWhenConfigured() {
        XaiLlmProvider provider = new XaiLlmProvider("api-key", "grok-4.6", 10, null, (WebClient) null, "low");

        var body = provider.buildRequestBody("prompt", LlmOptions.withTemperature(0.5));

        assertEquals("low", body.get("reasoning_effort"));
    }

    @Test
    void buildRequestBody_omitsReasoningEffortForNonReasoningModels() {
        XaiLlmProvider provider = new XaiLlmProvider("api-key", "grok-4.20-0309-non-reasoning", 10);

        var body = provider.buildRequestBody("prompt", LlmOptions.withTemperature(0.5));

        assertTrue(!body.containsKey("reasoning_effort"));
    }

    @Test
    void generate_noOAuthManager_usesApiKey() {
        List<String> authHeaders = new ArrayList<>();
        XaiLlmProvider provider = new XaiLlmProvider(
                "api-key", "grok-test", 10, null, recordingWebClient(authHeaders, chatResponse("hi")));

        String result = provider.generate("prompt", LlmOptions.withTemperature(0.5));

        assertEquals("hi", result);
        assertEquals(List.of("Bearer api-key"), authHeaders);
    }

    @Test
    void generate_oauthConfigured_prefersOAuthTokenOverApiKey() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager(
                "refresh-token", true, null, oauthWebClient(new AtomicInteger(), tokenResponse(3600)));
        XaiLlmProvider provider = new XaiLlmProvider(
                "api-key", "grok-test", 10, oauthManager, recordingWebClient(authHeaders, chatResponse("hi")));

        provider.generate("prompt", LlmOptions.withTemperature(0.5));

        assertEquals(List.of("Bearer oauth-access-token"), authHeaders);
    }

    @Test
    void generate_oauthNotConfigured_fallsBackToApiKey() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager("", true, null, oauthWebClient(new AtomicInteger(), tokenResponse(3600)));
        XaiLlmProvider provider = new XaiLlmProvider(
                "api-key", "grok-test", 10, oauthManager, recordingWebClient(authHeaders, chatResponse("hi")));

        provider.generate("prompt", LlmOptions.withTemperature(0.5));

        assertEquals(List.of("Bearer api-key"), authHeaders);
    }

    @Test
    void generate_oauthRejectedWith401_invalidatesAndRetriesWithApiKey() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager(
                "refresh-token", true, null, oauthWebClient(new AtomicInteger(), tokenResponse(3600)));
        XaiLlmProvider provider = new XaiLlmProvider(
                "api-key", "grok-test", 10, oauthManager,
                unauthorizedThenSuccessWebClient(authHeaders, chatResponse("recovered")));

        String result = provider.generate("prompt", LlmOptions.withTemperature(0.5));

        assertEquals("recovered", result);
        assertEquals(List.of("Bearer oauth-access-token", "Bearer api-key"), authHeaders);
    }

    @Test
    void generate_oauthUnavailableAndNoApiKey_throwsCleanlyInsteadOfCallingWithBlankAuth() {
        List<String> authHeaders = new ArrayList<>();
        XaiOAuthTokenManager oauthManager = new XaiOAuthTokenManager("", true, null, oauthWebClient(new AtomicInteger(), tokenResponse(3600)));
        XaiLlmProvider provider = new XaiLlmProvider(
                null, "grok-test", 10, oauthManager, recordingWebClient(authHeaders, chatResponse("unused")));

        assertThrows(LlmProviderException.class,
                () -> provider.generate("prompt", LlmOptions.withTemperature(0.5)));
        // No request should have been sent at all - never send "Bearer null".
        assertTrue(authHeaders.isEmpty());
    }

    private String chatResponse(String content) {
        return """
                {"choices":[{"message":{"content":"%s"}}]}
                """.formatted(content);
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

    private WebClient unauthorizedThenSuccessWebClient(List<String> authHeaders, String successBody) {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchangeFunction = request -> {
            captureAuthHeader(request, authHeaders);
            if (calls.getAndIncrement() == 0) {
                return Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"invalid token\"}")
                        .build());
            }
            return Mono.just(okResponse(successBody));
        };
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private WebClient oauthWebClient(AtomicInteger calls, String jsonBody) {
        ExchangeFunction exchangeFunction = request -> {
            calls.incrementAndGet();
            return Mono.just(okResponse(jsonBody));
        };
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
