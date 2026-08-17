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


@Service
public class BookCoverImageGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(BookCoverImageGeneratorService.class);

    private final ComfyUIService comfyUIService;
    private final XaiOAuthTokenManager oauthTokenManager;
    private final ImageGenerationHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${book-cover.generation.provider:comfyui}")
    private String provider;

    @Value("${book-cover.generation.timeout-seconds:180}")
    private int timeoutSeconds;

    @Value("${book-cover.xai.base-url:https://api.x.ai/v1}")
    private String xaiBaseUrl;

    @Value("${book-cover.xai.api-key:${XAI_API_KEY:}}")
    private String xaiApiKey;

    @Value("${book-cover.xai.model:grok-imagine-image}")
    private String xaiModel;

    @Value("${book-cover.xai.aspect-ratio:3:4}")
    private String xaiAspectRatio;

    @Value("${book-cover.xai.resolution:2k}")
    private String xaiResolution;

    @Value("${book-cover.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Value("${book-cover.openai.api-key:${OPENAI_API_KEY:}}")
    private String openaiApiKey;

    @Value("${book-cover.openai.model:gpt-image-1}")
    private String openaiModel;

    @Value("${book-cover.openai.size:1024x1536}")
    private String openaiSize;

    @Value("${book-cover.openai.quality:high}")
    private String openaiQuality;

    @Value("${book-cover.openai.output-format:png}")
    private String openaiOutputFormat;

    private WebClient xaiClient;
    private WebClient openaiClient;

    public BookCoverImageGeneratorService(
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
        this.openaiClient = httpClient.buildClient(openaiBaseUrl, openaiApiKey);
        log.info("Book cover image generator provider: {}", getProviderName());
    }

    public String getProviderName() {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "xai", "openai" -> normalized;
            default -> "comfyui";
        };
    }

    public String generateBookCover(String prompt, String outputFilename, String cacheKey) throws Exception {
        String safePrompt = ImagePromptSafety.prepareForGeneration(prompt);
        return switch (getProviderName()) {
            case "xai" -> generateWithXai(safePrompt, cacheKey);
            case "openai" -> generateWithOpenAi(safePrompt, cacheKey);
            default -> generateWithComfyUi(safePrompt, outputFilename, cacheKey);
        };
    }

    private String generateWithComfyUi(String prompt, String outputFilename, String cacheKey) throws Exception {
        String promptId = comfyUIService.submitBookCoverWorkflow(prompt, outputFilename, cacheKey);
        ComfyUIService.IllustrationResult result = comfyUIService.pollForBookCoverCompletion(promptId);
        if (!result.success()) {
            throw new IllegalStateException(result.errorMessage() == null ? "ComfyUI cover generation failed" : result.errorMessage());
        }
        return result.filename();
    }

    private String generateWithXai(String prompt, String cacheKey) throws Exception {
        Optional<String> oauthToken = oauthTokenManager != null
                ? oauthTokenManager.getAccessToken()
                : Optional.empty();
        String bearer = resolveXaiBearer(oauthToken, xaiApiKey);
        boolean usingOAuth = oauthToken.isPresent();
        log.info("event=book_cover_xai_request auth_source={}", usingOAuth ? "oauth" : "api_key");
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", xaiModel);
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("response_format", "b64_json");
        request.put("aspect_ratio", xaiAspectRatio);
        request.put("resolution", xaiResolution);

        try {
            byte[] imageBytes = postImageGenerationRequest(xaiClient, request, "xAI", bearer);
            return comfyUIService.saveBookCoverImage(cacheKey, imageBytes);
        } catch (WebClientResponseException e) {
            if (usingOAuth && e.getStatusCode().value() == 401) {
                oauthTokenManager.invalidate();
                if (xaiApiKey != null && !xaiApiKey.isBlank()) {
                    log.warn("event=book_cover_xai_oauth_rejected retrying_with=api_key");
                    byte[] imageBytes = postImageGenerationRequest(xaiClient, request, "xAI", xaiApiKey);
                    return comfyUIService.saveBookCoverImage(cacheKey, imageBytes);
                }
                log.warn("event=book_cover_xai_oauth_rejected no_api_key_fallback");
            }
            throw e;
        }
    }

    private String generateWithOpenAi(String prompt, String cacheKey) throws Exception {
        ensureConfigured(openaiApiKey, "OpenAI");
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", openaiModel);
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("size", openaiSize);
        request.put("quality", openaiQuality);
        request.put("output_format", openaiOutputFormat);

        byte[] imageBytes = postImageGenerationRequest(openaiClient, request, "OpenAI", null);
        return comfyUIService.saveBookCoverImage(cacheKey, imageBytes);
    }

    private byte[] postImageGenerationRequest(
            WebClient client, ObjectNode request, String providerName, String bearerToken) throws Exception {
        return httpClient.postAndDecodePng(client, request, providerName, bearerToken, timeoutSeconds);
    }

    static String resolveXaiBearer(Optional<String> oauthToken, String apiKey) {
        String bearer = oauthToken == null ? apiKey : oauthToken.orElse(apiKey);
        if (bearer == null || bearer.isBlank()) {
            throw new IllegalStateException(
                    "xAI cover generation unavailable: SuperGrok OAuth token missing and no XAI_API_KEY");
        }
        return bearer;
    }


    private void ensureConfigured(String apiKey, String providerName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(providerName + " API key is not configured for book cover generation");
        }
    }

}
