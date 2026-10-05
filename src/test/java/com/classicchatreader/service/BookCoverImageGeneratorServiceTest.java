package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookCoverImageGeneratorServiceTest {

    @Test
    void prefersSuperGrokOAuthOverApiKey() {
        assertEquals(
                "oauth-token",
                BookCoverImageGeneratorService.resolveXaiBearer(
                        Optional.of("oauth-token"), "api-key", "cover generation", () -> "unused"));
    }

    @Test
    void fallsBackToApiKeyWhenOAuthMissing() {
        assertEquals(
                "api-key",
                BookCoverImageGeneratorService.resolveXaiBearer(
                        Optional.empty(), "api-key", "cover generation", () -> "unused"));
    }

    @Test
    void failsWhenNeitherOAuthNorApiKeyIsPresent() {
        assertThrows(
                IllegalStateException.class,
                () -> BookCoverImageGeneratorService.resolveXaiBearer(
                        Optional.empty(), "  ", "cover generation", () -> "no token"));
    }

    @Test
    void unavailableErrorNamesTheFeatureAndTheReason() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> BookCoverImageGeneratorService.resolveXaiBearer(
                        Optional.empty(), null, "cover generation",
                        () -> "xAI rejected the SuperGrok OAuth refresh token (HTTP 400 invalid_grant)"));

        assertEquals(
                "xAI cover generation unavailable and no API key is configured as a fallback: "
                        + "xAI rejected the SuperGrok OAuth refresh token (HTTP 400 invalid_grant)",
                error.getMessage());
    }

    @Test
    void reasonIsNotEvaluatedWhenABearerIsAvailable() {
        assertEquals(
                "api-key",
                BookCoverImageGeneratorService.resolveXaiBearer(
                        Optional.empty(), "api-key", "cover generation",
                        () -> { throw new AssertionError("reason must not be computed when a bearer exists"); }));
    }
}
