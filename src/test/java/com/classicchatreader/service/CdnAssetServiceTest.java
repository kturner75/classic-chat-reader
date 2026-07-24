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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}