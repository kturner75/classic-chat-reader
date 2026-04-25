package com.classicchatreader.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/features")
public class FeatureController {
    @Value("${speed-reading.enabled:true}")
    private boolean speedReadingEnabled;

    @Value("${library.catalog.mode:curated}")
    private String catalogMode;

    @GetMapping
    public Map<String, Object> getFeatures() {
        Map<String, Object> features = new HashMap<>();
        features.put("speedReadingEnabled", speedReadingEnabled);
        features.put("catalogMode", catalogMode);
        features.put("catalogCuratedOnly", !"full".equalsIgnoreCase(catalogMode));
        return features;
    }
}
