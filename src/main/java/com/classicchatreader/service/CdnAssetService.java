package com.classicchatreader.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Service
public class CdnAssetService {

    private static final Duration ASSET_CHECK_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;

    @Value("${assets.cdn-base-url:}")
    private String cdnBaseUrl;

    @Value("${assets.cdn-prefix:assets}")
    private String cdnPrefix;

    public CdnAssetService() {
        this(HttpClient.newBuilder()
                .connectTimeout(ASSET_CHECK_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    CdnAssetService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public boolean isEnabled() {
        return cdnBaseUrl != null && !cdnBaseUrl.isBlank();
    }

    public Optional<String> buildAssetUrl(String assetKey) {
        return buildAssetUrl(null, assetKey);
    }

    public Optional<String> buildAssetUrl(String assetRoot, String assetKey) {
        if (!isEnabled() || assetKey == null || assetKey.isBlank()) {
            return Optional.empty();
        }

        String base = cdnBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String prefix = cdnPrefix == null ? "" : cdnPrefix.trim();
        if (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        String key = assetKey.trim();
        if (key.startsWith("/")) {
            key = key.substring(1);
        }

        String root = assetRoot == null ? "" : assetRoot.trim();
        if (root.startsWith("/")) {
            root = root.substring(1);
        }
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }

        String path = root.isBlank() ? key : root + "/" + key;
        String url = prefix.isBlank()
                ? base + "/" + path
                : base + "/" + prefix + "/" + path;
        return Optional.of(url);
    }

    public boolean assetExists(String assetRoot, String assetKey) {
        Optional<String> url = buildAssetUrl(assetRoot, assetKey);
        if (url.isEmpty()) {
            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.get()))
                    .timeout(ASSET_CHECK_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            int statusCode = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return statusCode >= 200 && statusCode < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
