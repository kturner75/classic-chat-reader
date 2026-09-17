package com.classicchatreader.service;

import org.springframework.stereotype.Service;
import java.util.UUID;

/** Disposable image generation: no entity, settings, queue, or live-slot writes. */
@Service
public class StylePreviewService {
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
            throw new IllegalArgumentException("Unknown preview family");
        if (prompt == null || prompt.isBlank() || prompt.length() > 12000)
            throw new IllegalArgumentException("Preview prompt must contain 1–12000 characters");
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
            cache.deleteStylePreview(family, key);
        }
    }
}
