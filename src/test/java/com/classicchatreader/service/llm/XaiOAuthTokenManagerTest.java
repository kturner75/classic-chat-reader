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

        assertEquals("rotated-refresh-token", readPersistedCurrentToken(tokenFile));
    }

    @Test
    void newManagerInstance_loadsRotatedRefreshTokenFromFile_whenConfiguredTokenUnchanged() throws Exception {
        // Simulates a process restart with an unchanged env var: a prior instance rotated
        // and persisted a new refresh token, and a fresh instance must use it instead of
        // the original configured value, which xAI has since invalidated.
        Path tokenFile = tempDir.resolve("refresh-token");
        writePersistedState(tokenFile, "stale-original-token-in-env-var", "previously-rotated-token");

        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "stale-original-token-in-env-var", true, tokenFile.toString(),
                countingWebClient(new AtomicInteger(), tokenResponse("access-token", 3600, null)));

        assertEquals("previously-rotated-token", manager.currentRefreshTokenForTesting());
    }

    @Test
    void newManagerInstance_prefersFreshlyConfiguredToken_whenItDiffersFromPersistedSeed() throws Exception {
        // Regression test for the code review finding: if an operator reruns
        // xai_oauth_login.sh and sets a new XAI_OAUTH_REFRESH_TOKEN (e.g. because the
        // persisted token was revoked/corrupted/copied from another deployment), that
        // fresh token must win over a stale persisted cache instead of being ignored.
        Path tokenFile = tempDir.resolve("refresh-token");
        writePersistedState(tokenFile, "old-configured-token", "old-rotated-token");

        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "newly-configured-token", true, tokenFile.toString(),
                countingWebClient(new AtomicInteger(), tokenResponse("access-token", 3600, null)));

        assertEquals("newly-configured-token", manager.currentRefreshTokenForTesting());
    }

    @Test
    void refresh_rotatedRefreshToken_persistsFileWithOwnerOnlyPermissions() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(
                "original-refresh-token", true, tokenFile.toString(),
                countingWebClient(new AtomicInteger(), tokenResponse("access-token", 3600, "rotated-refresh-token")));

        manager.getAccessToken();

        try {
            var permissions = java.nio.file.Files.getPosixFilePermissions(tokenFile);
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), permissions);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows CI) - nothing to verify.
        }
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

    @Test
    void describeUnavailability_disabled() {
        XaiOAuthTokenManager manager = manager("refresh-token", false, countingWebClient(new AtomicInteger(), tokenResponse("a", 3600, null)));

        assertEquals("SuperGrok OAuth is disabled (ai.xai.oauth.enabled=false)", manager.describeUnavailability());
    }

    @Test
    void describeUnavailability_notConfigured_saysHowToMintOne() {
        XaiOAuthTokenManager manager = manager("", true, countingWebClient(new AtomicInteger(), tokenResponse("a", 3600, null)));

        String message = manager.describeUnavailability();

        assertTrue(message.contains("no SuperGrok OAuth refresh token is configured"), message);
        assertTrue(message.contains("XAI_OAUTH_REFRESH_TOKEN"), message);
        assertTrue(message.contains("scripts/xai_oauth_login.sh"), message);
    }

    @Test
    void describeUnavailability_rejectedRefreshToken_namesInvalidGrantAndTheFix() {
        XaiOAuthTokenManager manager = manager("refresh-token-secret", true, statusWebClient(HttpStatus.BAD_REQUEST,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid or unknown refresh token\"}"));

        assertEquals(Optional.empty(), manager.getAccessToken());
        String message = manager.describeUnavailability();

        assertTrue(message.contains("xAI rejected the SuperGrok OAuth refresh token"), message);
        assertTrue(message.contains("HTTP 400 invalid_grant: Invalid or unknown refresh token"), message);
        assertTrue(message.contains("scripts/xai_oauth_login.sh"), message);
        assertFalse(message.contains("refresh-token-secret"), "the refresh token must never be in the message");
    }

    @Test
    void describeUnavailability_serverError_isTransientNotARejection() {
        XaiOAuthTokenManager manager = manager("refresh-token", true, statusWebClient(HttpStatus.SERVICE_UNAVAILABLE,
                "{\"error\":\"temporarily_unavailable\"}"));

        manager.getAccessToken();
        String message = manager.describeUnavailability();

        assertTrue(message.contains("the last SuperGrok OAuth refresh failed (HTTP 503 temporarily_unavailable)"), message);
        assertFalse(message.contains("rejected"), message);
        assertFalse(message.contains("xai_oauth_login"), "no need to re-mint a token for a transient failure: " + message);
    }

    @Test
    void describeUnavailability_networkError_isTransient() {
        ExchangeFunction exchange = request -> Mono.error(new IllegalStateException("connection reset by peer"));
        XaiOAuthTokenManager manager = manager("refresh-token", true, WebClient.builder().exchangeFunction(exchange).build());

        manager.getAccessToken();
        String message = manager.describeUnavailability();

        assertTrue(message.contains("the last SuperGrok OAuth refresh failed (connection reset by peer)"), message);
        assertFalse(message.contains("rejected"), message);
    }

    @Test
    void describeUnavailability_echoesOnlyTheErrorCodeAndDescription() {
        XaiOAuthTokenManager withExtraFields = manager("refresh-token", true, statusWebClient(HttpStatus.BAD_REQUEST,
                "{\"error\":\"invalid_grant\",\"error_description\":\"bad\",\"refresh_token\":\"leak-me\",\"debug\":\"leak-too\"}"));
        withExtraFields.getAccessToken();
        String message = withExtraFields.describeUnavailability();
        assertFalse(message.contains("leak-me") || message.contains("leak-too"), message);

        XaiOAuthTokenManager notJson = manager("refresh-token", true, statusWebClient(HttpStatus.BAD_REQUEST,
                "<html>secret internal page</html>"));
        notJson.getAccessToken();
        String htmlMessage = notJson.describeUnavailability();
        assertTrue(htmlMessage.contains("HTTP 400"), htmlMessage);
        assertFalse(htmlMessage.contains("secret internal page"), "a non-JSON body must not be echoed: " + htmlMessage);
    }

    @Test
    void describeUnavailability_longDescriptionIsTruncated() {
        String longText = "x".repeat(500);
        XaiOAuthTokenManager manager = manager("refresh-token", true, statusWebClient(HttpStatus.BAD_REQUEST,
                "{\"error\":\"invalid_grant\",\"error_description\":\"" + longText + "\"}"));

        manager.getAccessToken();

        assertTrue(manager.describeUnavailability().length() < 400, manager.describeUnavailability());
    }

    @Test
    void describeUnavailability_staticFormHandlesNoManager() {
        assertEquals("SuperGrok OAuth is not set up", XaiOAuthTokenManager.describeUnavailability(null));
    }

    private WebClient statusWebClient(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    private XaiOAuthTokenManager manager(String refreshToken, boolean enabled, WebClient webClient) {
        return new XaiOAuthTokenManager(refreshToken, enabled, null, webClient);
    }

    private void writePersistedState(Path tokenFile, String seedToken, String currentToken) throws Exception {
        Files.writeString(tokenFile, """
                {"seedToken":"%s","currentToken":"%s"}
                """.formatted(seedToken, currentToken));
    }

    private String readPersistedCurrentToken(Path tokenFile) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(tokenFile));
        return node.path("currentToken").asText(null);
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
