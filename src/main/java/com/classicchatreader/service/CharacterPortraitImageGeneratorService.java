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

import java.util.Optional;

/**
 * Generates character portraits through the configured image provider.
 *
 * <p>Mirrors {@link BookCoverImageGeneratorService} so portraits are not tied to a locally
 * running ComfyUI. Both providers write into the same portrait cache, so readers, transfers,
 * and the CDN path stay provider-agnostic.
 */
@Service
public class CharacterPortraitImageGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(CharacterPortraitImageGeneratorService.class);

    private final ComfyUIService comfyUIService;
    private final XaiOAuthTokenManager oauthTokenManager;
    private final ImageGenerationHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${character.portrait.generation.provider:comfyui}")
    private String provider;

    @Value("${character.portrait.generation.timeout-seconds:180}")
    private int timeoutSeconds;

    @Value("${character.portrait.xai.base-url:https://api.x.ai/v1}")
    private String xaiBaseUrl;

    @Value("${character.portrait.xai.api-key:${XAI_API_KEY:}}")
    private String xaiApiKey;

    @Value("${character.portrait.xai.model:grok-imagine-image}")
    private String xaiModel;

    @Value("${character.portrait.xai.aspect-ratio:3:4}")
    private String xaiAspectRatio;

    @Value("${character.portrait.xai.resolution:1k}")
    private String xaiResolution;

    private WebClient xaiClient;

    public CharacterPortraitImageGeneratorService(
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
        log.info("Character portrait image generator provider: {}", getProviderName());
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
     * local server; xAI needs either a SuperGrok OAuth token or an API key.
     */
    public boolean isAvailable() {
        if ("xai".equals(getProviderName())) {
            boolean hasOAuth = oauthTokenManager != null && oauthTokenManager.getAccessToken().isPresent();
            return hasOAuth || (xaiApiKey != null && !xaiApiKey.isBlank());
        }
        return comfyUIService.isAvailable();
    }

    /**
     * @return the cached portrait filename
     */
    public String generatePortrait(String prompt, String outputPrefix, String cacheKey) throws Exception {
        // Classroom guardrail must apply on every provider, not just the ComfyUI workflow
        // (which sanitizes again internally — prepareForGeneration is idempotent).
        String safePrompt = ImagePromptSafety.prepareForGeneration(prompt);
        if ("xai".equals(getProviderName())) {
            return generateWithXai(safePrompt, cacheKey);
        }
        return generateWithComfyUi(safePrompt, outputPrefix, cacheKey);
    }

    private String generateWithComfyUi(String prompt, String outputPrefix, String cacheKey) throws Exception {
        String promptId = comfyUIService.submitPortraitWorkflow(prompt, outputPrefix, cacheKey);
        ComfyUIService.IllustrationResult result = comfyUIService.pollForPortraitCompletion(promptId);
        if (!result.success()) {
            throw new IllegalStateException(result.errorMessage() == null
                    ? "ComfyUI portrait generation failed"
                    : result.errorMessage());
        }
        return result.filename();
    }

    private String generateWithXai(String prompt, String cacheKey) throws Exception {
        Optional<String> oauthToken = oauthTokenManager != null
                ? oauthTokenManager.getAccessToken()
                : Optional.empty();
        String bearer = BookCoverImageGeneratorService.resolveXaiBearer(oauthToken, xaiApiKey);
        boolean usingOAuth = oauthToken.isPresent();
        log.info("event=character_portrait_xai_request auth_source={}", usingOAuth ? "oauth" : "api_key");

        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", xaiModel);
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("response_format", "b64_json");
        request.put("aspect_ratio", xaiAspectRatio);
        request.put("resolution", xaiResolution);

        try {
            byte[] png = httpClient.postAndDecodePng(xaiClient, request, "xAI", bearer, timeoutSeconds);
            return comfyUIService.savePortraitImage(cacheKey, png);
        } catch (WebClientResponseException e) {
            // Mirrors the cover path: a rejected OAuth token is invalidated so the next call
            // re-mints, and we retry once on the API key when one is configured.
            if (usingOAuth && e.getStatusCode().value() == 401) {
                oauthTokenManager.invalidate();
                if (xaiApiKey != null && !xaiApiKey.isBlank()) {
                    log.warn("event=character_portrait_xai_oauth_rejected retrying_with=api_key");
                    byte[] png = httpClient.postAndDecodePng(xaiClient, request, "xAI", xaiApiKey, timeoutSeconds);
                    return comfyUIService.savePortraitImage(cacheKey, png);
                }
                log.warn("event=character_portrait_xai_oauth_rejected no_api_key_fallback");
            }
            throw e;
        }
    }
}
