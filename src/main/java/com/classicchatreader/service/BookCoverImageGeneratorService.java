package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;

@Service
public class BookCoverImageGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(BookCoverImageGeneratorService.class);

    private final ComfyUIService comfyUIService;
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

    public BookCoverImageGeneratorService(ComfyUIService comfyUIService) {
        this.comfyUIService = comfyUIService;
    }

    @PostConstruct
    public void init() {
        this.xaiClient = buildClient(xaiBaseUrl, xaiApiKey);
        this.openaiClient = buildClient(openaiBaseUrl, openaiApiKey);
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
        return switch (getProviderName()) {
            case "xai" -> generateWithXai(prompt, cacheKey);
            case "openai" -> generateWithOpenAi(prompt, cacheKey);
            default -> generateWithComfyUi(prompt, outputFilename, cacheKey);
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
        ensureConfigured(xaiApiKey, "xAI");
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", xaiModel);
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("response_format", "b64_json");
        request.put("aspect_ratio", xaiAspectRatio);
        request.put("resolution", xaiResolution);

        byte[] imageBytes = postImageGenerationRequest(xaiClient, request, "xAI");
        return comfyUIService.saveBookCoverImage(cacheKey, ensurePng(imageBytes));
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

        byte[] imageBytes = postImageGenerationRequest(openaiClient, request, "OpenAI");
        return comfyUIService.saveBookCoverImage(cacheKey, ensurePng(imageBytes));
    }

    private byte[] postImageGenerationRequest(WebClient client, ObjectNode request, String providerName) throws Exception {
        String response = client.post()
                .uri("/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(objectMapper.writeValueAsString(request))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(Math.max(30, timeoutSeconds)));

        JsonNode image = objectMapper.readTree(response).path("data").path(0);
        String b64 = image.path("b64_json").asText("");
        if (!b64.isBlank()) {
            return Base64.getDecoder().decode(b64);
        }

        String url = image.path("url").asText("");
        if (!url.isBlank()) {
            return WebClient.builder()
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                    .build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(Math.max(30, timeoutSeconds)));
        }

        throw new IllegalStateException(providerName + " image response did not include b64_json or url");
    }

    private WebClient buildClient(String baseUrl, String apiKey) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(64 * 1024 * 1024));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private void ensureConfigured(String apiKey, String providerName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(providerName + " API key is not configured for book cover generation");
        }
    }

    private byte[] ensurePng(byte[] imageBytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IllegalArgumentException("Generated book cover response was not a supported image");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("Unable to encode generated book cover as PNG");
        }
        return output.toByteArray();
    }
}
