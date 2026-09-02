package com.classicchatreader.service;

import com.classicchatreader.service.llm.XaiOAuthTokenManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Optional;

/**
 * Generates chapter illustrations through the configured image provider.
 *
 * <p>Mirrors {@link CharacterPortraitImageGeneratorService} so pregen illustrations
 * default to Grok Imagine. Both providers write into the same illustration cache.
 */
@Service
public class IllustrationImageGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(IllustrationImageGeneratorService.class);

    private final ComfyUIService comfyUIService;
    private final XaiOAuthTokenManager oauthTokenManager;
    private final ImageGenerationHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${illustration.generation.provider:xai}")
    private String provider;

    @Value("${illustration.generation.timeout-seconds:180}")
    private int timeoutSeconds;

    @Value("${illustration.xai.base-url:https://api.x.ai/v1}")
    private String xaiBaseUrl;

    @Value("${illustration.xai.api-key:${XAI_API_KEY:}}")
    private String xaiApiKey;

    @Value("${illustration.xai.model:grok-imagine-image}")
    private String xaiModel;

    @Value("${illustration.xai.aspect-ratio:3:4}")
    private String xaiAspectRatio;

    @Value("${illustration.xai.resolution:1k}")
    private String xaiResolution;

    private WebClient xaiClient;

    public IllustrationImageGeneratorService(
            ComfyUIService comfyUIService,
            XaiOAuthTokenManager oauthTokenManager,
            ImageGenerationHttpClient httpClient) {
        this.comfyUIService = comfyUIService;
        this.oauthTokenManager = oauthTokenManager;
        this.httpClient = httpClient;
    }

    @PostConstruct
    public void init() {
        this.xaiClient = httpClient.buildClient(xaiBaseUrl, null);
        log.info("Illustration image generator provider: {}", getProviderName());
    }

    public String getProviderName() {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "xai" -> normalized;
            default -> "comfyui";
        };
    }

    /**
     * True when the configured provider can currently accept work. ComfyUI needs a reachable
     * local server; xAI needs SuperGrok OAuth configured or an API key.
     *
     * <p>Do not call {@link XaiOAuthTokenManager#getAccessToken()} here. Status polls hit this
     * path, and minting a token just to render feature flags can block for the refresh timeout
     * and rotate the refresh credential. Actual token exchange stays in {@link #generateWithXai}.
     */
    public boolean isAvailable() {
        if ("xai".equals(getProviderName())) {
            boolean hasOAuth = oauthTokenManager != null && oauthTokenManager.isConfigured();
            return hasOAuth || (xaiApiKey != null && !xaiApiKey.isBlank());
        }
        return comfyUIService.isAvailable();
    }

    /**
     * @return the cached illustration filename
     */
    public String generateIllustration(String prompt, String outputPrefix, String cacheKey) throws Exception {
        return generateIllustration(prompt, outputPrefix, cacheKey, List.of());
    }

    public String generateIllustration(
            String prompt,
            String outputPrefix,
            String cacheKey,
            List<IllustrationPortraitReferences.PortraitRef> portraitRefs) throws Exception {
        String platePrompt = IllustrationPromptService.ensureNarrativePlate(prompt);
        if ("xai".equals(getProviderName())) {
            return generateWithXai(platePrompt, cacheKey, portraitRefs == null ? List.of() : portraitRefs);
        }
        if (portraitRefs != null && !portraitRefs.isEmpty()) {
            log.info("Skipping {} portrait refs; ComfyUI illustrations are text-only", portraitRefs.size());
        }
        return generateWithComfyUi(platePrompt, outputPrefix, cacheKey);
    }

    private String generateWithComfyUi(String prompt, String outputPrefix, String cacheKey) throws Exception {
        String promptId = comfyUIService.submitWorkflow(prompt, outputPrefix, cacheKey);
        ComfyUIService.IllustrationResult result = comfyUIService.pollForCompletion(promptId);
        if (!result.success()) {
            throw new IllegalStateException(result.errorMessage() == null
                    ? "ComfyUI illustration generation failed"
                    : result.errorMessage());
        }
        return result.filename();
    }

    private String generateWithXai(
            String prompt,
            String cacheKey,
            List<IllustrationPortraitReferences.PortraitRef> portraitRefs) throws Exception {
        Optional<String> oauthToken = oauthTokenManager != null
                ? oauthTokenManager.getAccessToken()
                : Optional.empty();
        String bearer = BookCoverImageGeneratorService.resolveXaiBearer(oauthToken, xaiApiKey);
        boolean usingOAuth = oauthToken.isPresent();
        if (!portraitRefs.isEmpty()) {
            log.info("event=illustration_xai_request skipping_portrait_bytes n={} reason=edits_would_reuse_portrait",
                    portraitRefs.size());
        }
        log.info("event=illustration_xai_request auth_source={}", usingOAuth ? "oauth" : "api_key");

        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", xaiModel);
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("response_format", "b64_json");
        request.put("aspect_ratio", xaiAspectRatio);
        request.put("resolution", xaiResolution);
        String path = "/images/generations";

        try {
            byte[] png = httpClient.postAndDecodePng(xaiClient, request, "xAI", bearer, timeoutSeconds, path);
            return comfyUIService.saveIllustrationImage(cacheKey, png);
        } catch (WebClientResponseException e) {
            if (usingOAuth && e.getStatusCode().value() == 401) {
                oauthTokenManager.invalidate();
                if (xaiApiKey != null && !xaiApiKey.isBlank()) {
                    log.warn("event=illustration_xai_oauth_rejected retrying_with=api_key");
                    byte[] png = httpClient.postAndDecodePng(
                            xaiClient, request, "xAI", xaiApiKey, timeoutSeconds, path);
                    return comfyUIService.saveIllustrationImage(cacheKey, png);
                }
                log.warn("event=illustration_xai_oauth_rejected no_api_key_fallback");
            }
            throw e;
        }
    }

}
