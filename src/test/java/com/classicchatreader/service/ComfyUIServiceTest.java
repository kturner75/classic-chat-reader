package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ComfyUIServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void chapterWorkflowSendsThePromptAsWritten() throws Exception {
        String chapterPrompt = "Victorian parlor, an adolescent girl and her romantic friend sewing by the window";
        assertThat(ImagePromptSafety.isBlocked(chapterPrompt)).isTrue();
        assertThat(ImagePromptSafety.prepareForGeneration(chapterPrompt))
                .isNotEqualTo(chapterPrompt);

        AtomicReference<String> capturedBody = new AtomicReference<>();
        ComfyUIService service = serviceCapturing(capturedBody);

        String promptId = service.submitWorkflow(chapterPrompt, "illustration_ch1", "cache-key");

        assertThat(promptId).isEqualTo("prompt-1");
        String sent = clipPositiveText(capturedBody.get());
        assertThat(sent).isEqualTo(chapterPrompt);
        assertThat(sent).doesNotContain("School-appropriate book illustration");
        assertThat(sent).doesNotContain("distant architecture and landscape only");
        assertThat(sent).doesNotContain("no figures");
        assertThat(sent).doesNotContain(ImagePromptSafety.SUFFIX.trim());
    }

    @Test
    void portraitWorkflowStillSanitizesThePrompt() throws Exception {
        String portraitPrompt = "a nude portrait of a character";
        AtomicReference<String> capturedBody = new AtomicReference<>();
        ComfyUIService service = serviceCapturing(capturedBody);

        service.submitPortraitWorkflow(portraitPrompt, "portrait_abc", "cache-key");

        String sent = clipPositiveText(capturedBody.get());
        assertThat(sent).isEqualTo(ImagePromptSafety.prepareForGeneration(portraitPrompt));
        assertThat(sent).doesNotContain("nude");
        assertThat(sent).contains("School-appropriate book illustration");
    }

    @Test
    void bookCoverWorkflowStillSanitizesThePrompt() throws Exception {
        String coverPrompt = "oil painting, a nude woman reclining on a bed";
        AtomicReference<String> capturedBody = new AtomicReference<>();
        ComfyUIService service = serviceCapturing(capturedBody);

        service.submitBookCoverWorkflow(coverPrompt, "cover_abc", "cache-key");

        String sent = clipPositiveText(capturedBody.get());
        assertThat(sent).isEqualTo(ImagePromptSafety.prepareForGeneration(coverPrompt));
        assertThat(sent).doesNotContain("nude");
        assertThat(sent).contains("School-appropriate book illustration");
    }

    private static ComfyUIService serviceCapturing(AtomicReference<String> capturedBody) {
        ComfyUIService service = new ComfyUIService();
        ReflectionTestUtils.setField(service, "checkpoint", "test.safetensors");
        ReflectionTestUtils.setField(service, "imageWidth", 768);
        ReflectionTestUtils.setField(service, "imageHeight", 1024);
        ReflectionTestUtils.setField(service, "samplerSteps", 20);
        ReflectionTestUtils.setField(service, "cfgScale", 7);
        ReflectionTestUtils.setField(service, "portraitWidth", 512);
        ReflectionTestUtils.setField(service, "portraitHeight", 640);
        ReflectionTestUtils.setField(service, "bookCoverWidth", 768);
        ReflectionTestUtils.setField(service, "bookCoverHeight", 1024);

        ExchangeFunction exchangeFunction = request -> {
            capturedBody.set(extractBody(request));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"prompt_id\":\"prompt-1\"}")
                    .build());
        };
        ReflectionTestUtils.setField(service, "webClient",
                WebClient.builder().exchangeFunction(exchangeFunction).build());
        return service;
    }

    private static String clipPositiveText(String requestBody) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(requestBody);
        return root.path("prompt").path("6").path("inputs").path("text").asText();
    }

    private static String extractBody(ClientRequest request) {
        MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
        request.body().insert(mock, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<org.springframework.http.server.reactive.ServerHttpResponse> serverResponse() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block();
        return mock.getBodyAsString().block();
    }
}
