package com.classicchatreader.service.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaiOAuthTokenManagerTest {

    @Test
    void getAccessToken_notConfigured_returnsEmptyWithoutNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager("", true, countingWebClient(calls, tokenResponse(3600)));

        assertFalse(manager.isConfigured());
        assertEquals(Optional.empty(), manager.getAccessToken());
        assertEquals(0, calls.get());
    }

    @Test
    void getAccessToken_disabled_returnsEmptyWithoutNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager("refresh-token", false, countingWebClient(calls, tokenResponse(3600)));

        assertFalse(manager.isConfigured());
        assertEquals(Optional.empty(), manager.getAccessToken());
        assertEquals(0, calls.get());
    }

    @Test
    void getAccessToken_configured_refreshesAndCachesToken() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "refresh-token", true, countingWebClient(calls, tokenResponse(3600)));

        assertTrue(manager.isConfigured());
        assertEquals(Optional.of("access-token"), manager.getAccessToken());
        assertEquals(Optional.of("access-token"), manager.getAccessToken());
        // Second call must be served from cache, not a second network round-trip.
        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_shortLivedToken_stillCachesInsteadOfImmediatelyExpiring() {
        // Regression test: a flat 1-hour refresh skew would make a 3600s-lifetime token
        // immediately expired, defeating the cache on every call.
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "refresh-token", true, countingWebClient(calls, tokenResponse(3600)));

        manager.getAccessToken();
        manager.getAccessToken();
        manager.getAccessToken();

        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_refreshFails_returnsEmptyAndDoesNotThrow() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "refresh-token", true, countingWebClient(calls, errorResponse()));

        Optional<String> result = manager.getAccessToken();

        assertEquals(Optional.empty(), result);
        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_afterFailure_respectsCooldownBeforeRetrying() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "refresh-token", true, countingWebClient(calls, errorResponse()));

        manager.getAccessToken();
        manager.getAccessToken();

        // Second call within the cooldown window should not re-hit the network.
        assertEquals(1, calls.get());
    }

    @Test
    void invalidate_forcesNextCallToRefresh() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "refresh-token", true, countingWebClient(calls, tokenResponse(3600)));

        manager.getAccessToken();
        manager.invalidate();
        manager.getAccessToken();

        assertEquals(2, calls.get());
    }

    private String tokenResponse(int expiresInSeconds) {
        return """
                {"access_token":"access-token","expires_in":%d,"token_type":"Bearer"}
                """.formatted(expiresInSeconds);
    }

    private WebClient countingWebClient(AtomicInteger calls, String jsonBody) {
        ExchangeFunction exchangeFunction = request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(jsonBody)
                    .build());
        };
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    private String errorResponse() {
        return "{\"error\":\"invalid_grant\"}";
    }
}
