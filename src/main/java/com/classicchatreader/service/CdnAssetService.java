package com.classicchatreader.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CdnAssetService {

    @Value("${assets.cdn-base-url:}")
    private String cdnBaseUrl;

    @Value("${assets.cdn-prefix:assets}")
    private String cdnPrefix;

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
}
