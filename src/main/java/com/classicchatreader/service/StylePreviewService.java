package com.classicchatreader.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.UUID;

/** Disposable image generation: no entity, settings, queue, or live-slot writes. */
@Service
public class StylePreviewService {
    private static final Logger log = LoggerFactory.getLogger(StylePreviewService.class);

    public static class InvalidPreviewRequest extends IllegalArgumentException {
        public InvalidPreviewRequest(String message) { super(message); }
    }

    private final BookCoverImageGeneratorService covers;
    private final CharacterPortraitImageGeneratorService portraits;
    private final IllustrationImageGeneratorService illustrations;
    private final ComfyUIService cache;

    public StylePreviewService(BookCoverImageGeneratorService covers, CharacterPortraitImageGeneratorService portraits,
            IllustrationImageGeneratorService illustrations, ComfyUIService cache) {
        this.covers = covers;
        this.portraits = portraits;
        this.illustrations = illustrations;
        this.cache = cache;
    }

    public byte[] generate(String family, String prompt) throws Exception {
        if (!java.util.Set.of("cover", "portrait", "illustration").contains(family))
            throw new InvalidPreviewRequest("Unknown preview family");
        if (prompt == null || prompt.isBlank() || prompt.length() > 12000)
            throw new InvalidPreviewRequest("Preview prompt must contain 1–12000 characters");
        String key = "style-preview-" + UUID.randomUUID();
        try {
            String filename = switch (family) {
                case "cover" -> covers.generateBookCover(prompt, key, key);
                case "portrait" -> portraits.generatePortrait(prompt, key, key);
                default -> illustrations.generateIllustration(prompt, key, key);
            };
            byte[] bytes = switch (family) {
                case "cover" -> cache.getBookCoverImage(filename);
                case "portrait" -> cache.getPortraitImage(filename);
                default -> cache.getImage(filename);
            };
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("Preview image was not available");
            return bytes;
        } finally {
            try {
                cache.deleteStylePreview(family, key);
            } catch (IOException cleanupFailure) {
                log.warn("Failed to delete style preview cache file {} ({})", key, family, cleanupFailure);
            }
        }
    }
}
