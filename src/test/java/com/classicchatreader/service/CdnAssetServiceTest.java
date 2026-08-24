package com.classicchatreader.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CdnAssetServiceTest {

    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<Void> response;

    private CdnAssetService service;

    @BeforeEach
    void setUp() {
        service = new CdnAssetService(httpClient);
        ReflectionTestUtils.setField(service, "cdnBaseUrl", "https://cdn.example.com");
        ReflectionTestUtils.setField(service, "cdnPrefix", "assets");
    }

    @Test
    void assetExists_usesHeadAgainstResolvedCdnUrl() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    assertEquals("HEAD", request.method());
                    assertEquals(
                            "https://cdn.example.com/assets/illustrations/books/gutenberg/1342/chapter.png",
                            request.uri().toString());
                    return response;
                });

        assertTrue(service.assetExists(
                "illustrations",
                "books/gutenberg/1342/chapter.png"));
    }

    @Test
    void assetExists_returnsFalseForMissingAsset() throws Exception {
        when(response.statusCode()).thenReturn(404);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertFalse(service.assetExists("illustrations", "missing.png"));
    }

    @Test
    void buildAssetUrl_withoutVersion_omitsQuery() {
        Optional<String> url = service.buildAssetUrl(
                "illustrations",
                "books/gutenberg/84/3.png");

        assertEquals(
                "https://cdn.example.com/assets/illustrations/books/gutenberg/84/3.png",
                url.orElseThrow());
    }

    @Test
    void buildAssetUrl_appendsStableCacheBusterFromFilenameAndCompletedAt() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        String key = "books/gutenberg/84/3.png";

        String first = service.buildAssetUrl("illustrations", key, completedAt).orElseThrow();
        String second = service.buildAssetUrl(
                "illustrations",
                new CdnAssetService.VersionedAsset(key, completedAt)).orElseThrow();

        assertTrue(first.contains("?v="), first);
        assertEquals(first, second);
        assertEquals(
                "https://cdn.example.com/assets/illustrations/books/gutenberg/84/3.png",
                first.substring(0, first.indexOf('?')));
        assertEquals(CdnAssetService.cacheBuster(key, completedAt), queryVersion(first));
    }

    @Test
    void buildAssetUrl_cacheBusterChangesWhenCompletedAtChanges() {
        String key = "books/gutenberg/84/3.png";
        String january = service.buildAssetUrl(
                "illustrations",
                key,
                LocalDateTime.of(2026, 1, 15, 12, 0, 0)).orElseThrow();
        String august = service.buildAssetUrl(
                "illustrations",
                key,
                LocalDateTime.of(2026, 8, 24, 18, 30, 0)).orElseThrow();

        assertNotEquals(queryVersion(january), queryVersion(august));
        assertEquals(
                january.substring(0, january.indexOf('?')),
                august.substring(0, august.indexOf('?')));
    }

    @Test
    void buildAssetUrl_nullCompletedAtStillAddsFilenameCacheBuster() {
        String url = service.buildAssetUrl(
                "character-portraits",
                "books/gutenberg/84/elizabeth.png",
                null).orElseThrow();

        assertTrue(url.contains("?v="), url);
        assertTrue(url.startsWith("https://cdn.example.com/assets/character-portraits/"));
        assertEquals("0-" + Integer.toUnsignedString(
                "books/gutenberg/84/elizabeth.png".hashCode(), 36), queryVersion(url));
    }

    @Test
    void buildAssetUrl_cacheBusterChangesWhenFilenameChanges() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 8, 24, 18, 30, 0);
        String original = service.buildAssetUrl(
                "illustrations",
                "books/gutenberg/84/3.png",
                completedAt).orElseThrow();
        String renamed = service.buildAssetUrl(
                "illustrations",
                "books/gutenberg/84/3-v20260824.png",
                completedAt).orElseThrow();

        assertNotEquals(queryVersion(original), queryVersion(renamed));
    }

    private static String queryVersion(String url) {
        int queryAt = url.indexOf("?v=");
        assertTrue(queryAt >= 0, url);
        return url.substring(queryAt + 3);
    }
}
