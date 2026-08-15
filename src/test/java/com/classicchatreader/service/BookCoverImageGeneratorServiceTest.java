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
                BookCoverImageGeneratorService.resolveXaiBearer(Optional.of("oauth-token"), "api-key"));
    }

    @Test
    void fallsBackToApiKeyWhenOAuthMissing() {
        assertEquals(
                "api-key",
                BookCoverImageGeneratorService.resolveXaiBearer(Optional.empty(), "api-key"));
    }

    @Test
    void failsWhenNeitherOAuthNorApiKeyIsPresent() {
        assertThrows(
                IllegalStateException.class,
                () -> BookCoverImageGeneratorService.resolveXaiBearer(Optional.empty(), "  "));
    }
}
