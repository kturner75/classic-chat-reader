package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;

/**
 * Shared transport for OpenAI-compatible {@code /images/generations} endpoints (xAI Grok
 * Imagine, OpenAI). Callers own the request body and provider selection; this only handles
 * the POST, the b64/url response split, and PNG normalisation.
 */
@Component
public class ImageGenerationHttpClient {

    private static final int MAX_IN_MEMORY_BYTES = 64 * 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebClient buildClient(String baseUrl, String apiKey) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    public byte[] postAndDecodePng(
            WebClient client,
            ObjectNode request,
            String providerName,
            String bearerToken,
            int timeoutSeconds) throws Exception {
        return ensurePng(postImageGenerationRequest(client, request, providerName, bearerToken, timeoutSeconds));
    }

    private byte[] postImageGenerationRequest(
            WebClient client,
            ObjectNode request,
            String providerName,
            String bearerToken,
            int timeoutSeconds) throws Exception {
        Duration timeout = Duration.ofSeconds(Math.max(30, timeoutSeconds));
        var spec = client.post()
                .uri("/images/generations")
                .contentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null && !bearerToken.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + bearerToken);
        }
        String response = spec
                .bodyValue(objectMapper.writeValueAsString(request))
                .retrieve()
                .bodyToMono(String.class)
                .block(timeout);

        JsonNode image = objectMapper.readTree(response).path("data").path(0);
        String b64 = image.path("b64_json").asText("");
        if (!b64.isBlank()) {
            return Base64.getDecoder().decode(b64);
        }

        String url = image.path("url").asText("");
        if (!url.isBlank()) {
            return WebClient.builder()
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                    .build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(timeout);
        }

        throw new IllegalStateException(providerName + " image response did not include b64_json or url");
    }

    public byte[] ensurePng(byte[] imageBytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IllegalArgumentException("Generated image response was not a supported image");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("Unable to encode generated image as PNG");
        }
        return output.toByteArray();
    }
}
