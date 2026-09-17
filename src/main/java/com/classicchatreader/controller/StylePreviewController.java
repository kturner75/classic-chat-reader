package com.classicchatreader.controller;

import com.classicchatreader.service.StylePreviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/style-previews")
public class StylePreviewController {
    private final StylePreviewService previews;
    private final boolean cacheOnly;

    public StylePreviewController(StylePreviewService previews, @Value("${generation.cache-only:false}") boolean cacheOnly) {
        this.previews = previews;
        this.cacheOnly = cacheOnly;
    }

    @GetMapping
    public Map<String, Object> capabilities() {
        return Map.of("available", !cacheOnly, "version", 1);
    }

    public record Request(String family, String prompt) {}

    @PostMapping
    public ResponseEntity<?> generate(@RequestBody Request request) throws Exception {
        if (cacheOnly) return ResponseEntity.status(409).body(Map.of("error", "Preview generation is unavailable in cache-only mode"));
        if (request.family() == null) return ResponseEntity.badRequest().body(Map.of("error", "Preview family is required"));
        try {
            byte[] bytes = previews.generate(request.family(), request.prompt());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        }
    }
}
