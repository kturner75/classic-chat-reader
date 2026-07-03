package com.classicchatreader.service.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaiOAuthTokenManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void getAccessToken_notConfigured_returnsEmptyWithoutNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("", true, countingWebClient(calls, tokenResponse("access-token", 3600, null)));

        assertFalse(manager.isConfigured());
        assertEquals(Optional.empty(), manager.getAccessToken());
        assertEquals(0, calls.get());
    }

    @Test
    void getAccessToken_disabled_returnsEmptyWithoutNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", false, countingWebClient(calls, tokenResponse("access-token", 3600, null)));

        assertFalse(manager.isConfigured());
        assertEquals(Optional.empty(), manager.getAccessToken());
        assertEquals(0, calls.get());
    }

    @Test
    void getAccessToken_configured_refreshesAndCachesToken() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingWebClient(calls, tokenResponse("access-token", 3600, null)));

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
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingWebClient(calls, tokenResponse("access-token", 3600, null)));

        manager.getAccessToken();
        manager.getAccessToken();
        manager.getAccessToken();

        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_refreshFails_returnsEmptyAndDoesNotThrow() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingWebClient(calls, errorResponse()));

        Optional<String> result = manager.getAccessToken();

        assertEquals(Optional.empty(), result);
        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_afterFailure_respectsCooldownBeforeRetrying() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingWebClient(calls, errorResponse()));

        manager.getAccessToken();
        manager.getAccessToken();

        // Second call within the cooldown window should not re-hit the network.
        assertEquals(1, calls.get());
    }

    @Test
    void invalidate_forcesNextCallToRefresh() {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingWebClient(calls, tokenResponse("access-token", 3600, null)));

        manager.getAccessToken();
        manager.invalidate();
        manager.getAccessToken();

        assertEquals(2, calls.get());
    }

    @Test
    void refresh_rotatedRefreshToken_isPersistedToFile() throws Exception {
        // Regression test for the prod incident: xAI rotates the refresh token on every
        // use, and the rotated value must be persisted so a restart doesn't retry the
        // now-invalidated original token.
        Path tokenFile = tempDir.resolve("refresh-token");
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "original-refresh-token", true, tokenFile.toString(),
                countingWebClient(calls, tokenResponse("access-token", 3600, "rotated-refresh-token")));

        manager.getAccessToken();

        assertEquals("rotated-refresh-token", Files.readString(tokenFile).trim());
    }

    @Test
    void newManagerInstance_loadsRotatedRefreshTokenFromFile() throws Exception {
        // Simulates a process restart: a prior instance rotated and persisted a new
        // refresh token, and a fresh instance must use it instead of the original
        // configured value, which xAI has since invalidated.
        Path tokenFile = tempDir.resolve("refresh-token");
        Files.writeString(tokenFile, "previously-rotated-token");

        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = countingWebClient(calls, tokenResponse("access-token", 3600, null));

        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "stale-original-token-in-env-var", true, tokenFile.toString(), webClient);

        assertTrue(manager.isConfigured());
        assertEquals(Optional.of("access-token"), manager.getAccessToken());
        assertEquals(1, calls.get());
    }

    @Test
    void refresh_responseWithoutRotatedToken_leavesFileUntouched() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "original-refresh-token", true, tokenFile.toString(),
                countingWebClient(calls, tokenResponse("access-token", 3600, null)));

        manager.getAccessToken();

        assertFalse(Files.exists(tokenFile));
    }

    private XaiOAuthTokenManager manager(String refreshToken, boolean enabled, WebClient webClient) {
        return new XaiOAuthTokenManager(refreshToken, enabled, null, webClient);
    }

    private String tokenResponse(String accessToken, int expiresInSeconds, String rotatedRefreshToken) {
        String refreshTokenField = rotatedRefreshToken != null
                ? ",\"refresh_token\":\"" + rotatedRefreshToken + "\""
                : "";
        return """
                {"access_token":"%s","expires_in":%d,"token_type":"Bearer"%s}
                """.formatted(accessToken, expiresInSeconds, refreshTokenField);
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
