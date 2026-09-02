package com.classicchatreader.service;

import com.classicchatreader.service.llm.XaiOAuthTokenManager;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IllustrationImageGeneratorServiceTest {

    @Mock private ComfyUIService comfyUIService;
    @Mock private XaiOAuthTokenManager oauthTokenManager;
    @Mock private ImageGenerationHttpClient httpClient;

    private IllustrationImageGeneratorService service;

    @BeforeEach
    void setUp() {
        service = new IllustrationImageGeneratorService(comfyUIService, oauthTokenManager, httpClient);
        ReflectionTestUtils.setField(service, "xaiApiKey", "");
        ReflectionTestUtils.setField(service, "xaiModel", "grok-imagine-image");
        ReflectionTestUtils.setField(service, "xaiAspectRatio", "3:4");
        ReflectionTestUtils.setField(service, "xaiResolution", "1k");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 180);
    }

    private void useProvider(String provider) {
        ReflectionTestUtils.setField(service, "provider", provider);
    }

    @Test
    void defaultsToXaiFromUnsetProviderField() {
        useProvider("xai");
        assertThat(service.getProviderName()).isEqualTo("xai");
    }

    @Test
    void defaultsToComfyUiForUnknownProvider() {
        useProvider("something-else");
        assertThat(service.getProviderName()).isEqualTo("comfyui");
    }

    @Test
    void xaiIsAvailableWithOAuthAndNoApiKey() {
        useProvider("xai");
        when(oauthTokenManager.isConfigured()).thenReturn(true);

        assertThat(service.isAvailable()).isTrue();
        verify(comfyUIService, never()).isAvailable();
        verify(oauthTokenManager, never()).getAccessToken();
    }

    @Test
    void xaiGenerationSavesIntoTheIllustrationCache() throws Exception {
        useProvider("xai");
        when(oauthTokenManager.getAccessToken()).thenReturn(Optional.of("oauth-token"));
        when(httpClient.postAndDecodePng(any(), any(), anyString(), anyString(), any(Integer.class), anyString()))
                .thenReturn(new byte[] {1, 2, 3});
        when(comfyUIService.saveIllustrationImage(anyString(), any())).thenReturn("cached/ch1.png");

        String filename = service.generateIllustration("a chapter scene", "illustration_ch1", "cache-key");

        assertThat(filename).isEqualTo("cached/ch1.png");
        verify(comfyUIService, never()).submitWorkflow(anyString(), anyString(), anyString());
    }

    @Test
    void xaiGenerationSanitizesThePromptBeforeSending() throws Exception {
        useProvider("xai");
        when(oauthTokenManager.getAccessToken()).thenReturn(Optional.of("oauth-token"));
        when(httpClient.postAndDecodePng(any(), any(), anyString(), anyString(), any(Integer.class), anyString()))
                .thenReturn(new byte[] {1, 2, 3});
        when(comfyUIService.saveIllustrationImage(anyString(), any())).thenReturn("cached/x.png");

        ArgumentCaptor<ObjectNode> request = ArgumentCaptor.forClass(ObjectNode.class);
        service.generateIllustration("a storm over the lake", "illustration_ch1", "cache-key");

        verify(httpClient).postAndDecodePng(
                any(), request.capture(), anyString(), anyString(), any(Integer.class), anyString());
        String sent = request.getValue().path("prompt").asText();
        assertThat(sent).startsWith("a storm over the lake");
        assertThat(sent).contains("not a character portrait");
        assertThat(sent).isEqualTo(
                IllustrationPromptService.ensureNarrativePlate("a storm over the lake"));
        assertThat(sent).doesNotContain("School-appropriate book illustration");
        assertThat(sent).doesNotContain("distant architecture and landscape only");
        assertThat(request.getValue().path("model").asText()).isEqualTo("grok-imagine-image");
    }

    @Test
    void xaiDoesNotSendPortraitBytesOnEdits() throws Exception {
        useProvider("xai");
        when(oauthTokenManager.getAccessToken()).thenReturn(Optional.of("oauth-token"));
        when(httpClient.postAndDecodePng(any(), any(), anyString(), anyString(), any(Integer.class), anyString()))
                .thenReturn(new byte[] {1, 2, 3});
        when(comfyUIService.saveIllustrationImage(anyString(), any())).thenReturn("cached/ch1.png");

        ArgumentCaptor<ObjectNode> request = ArgumentCaptor.forClass(ObjectNode.class);
        var polly = new IllustrationPortraitReferences.PortraitRef("Polly Milton", new byte[] {9, 8, 7});
        service.generateIllustration(
                "Polly at the window",
                "illustration_ch1",
                "cache-key",
                List.of(polly));

        verify(httpClient).postAndDecodePng(
                any(), request.capture(), anyString(), anyString(), any(Integer.class),
                org.mockito.ArgumentMatchers.eq("/images/generations"));
        assertThat(request.getValue().path("image").isMissingNode()).isTrue();
        assertThat(request.getValue().path("images").isMissingNode()).isTrue();
    }

    @Test
    void comfyUiGenerationUsesTheWorkflow() throws Exception {
        useProvider("comfyui");
        when(comfyUIService.submitWorkflow(anyString(), anyString(), anyString())).thenReturn("prompt-1");
        when(comfyUIService.pollForCompletion("prompt-1"))
                .thenReturn(new ComfyUIService.IllustrationResult(true, "cached/ch1.png", null));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        String filename = service.generateIllustration("a chapter scene", "illustration_ch1", "cache-key");

        assertThat(filename).isEqualTo("cached/ch1.png");
        verify(comfyUIService).submitWorkflow(prompt.capture(), anyString(), anyString());
        assertThat(prompt.getValue()).isEqualTo(
                IllustrationPromptService.ensureNarrativePlate("a chapter scene"));
        assertThat(prompt.getValue()).doesNotContain("School-appropriate book illustration");
    }

    @Test
    void xaiGenerationDoesNotReplaceChapterPromptWithEmptyLandscape() throws Exception {
        useProvider("xai");
        when(oauthTokenManager.getAccessToken()).thenReturn(Optional.of("oauth-token"));
        when(httpClient.postAndDecodePng(any(), any(), anyString(), anyString(), any(Integer.class), anyString()))
                .thenReturn(new byte[] {1, 2, 3});
        when(comfyUIService.saveIllustrationImage(anyString(), any())).thenReturn("cached/x.png");

        ArgumentCaptor<ObjectNode> request = ArgumentCaptor.forClass(ObjectNode.class);
        service.generateIllustration(
                "a child kissing her mother in the parlor", "illustration_ch1", "cache-key");

        verify(httpClient).postAndDecodePng(
                any(), request.capture(), anyString(), anyString(), any(Integer.class), anyString());
        String sent = request.getValue().path("prompt").asText();
        assertThat(sent).contains("a child kissing her mother in the parlor");
        assertThat(sent).doesNotContain("distant architecture and landscape only");
        assertThat(sent).doesNotContain("School-appropriate book illustration");
        assertThat(sent).doesNotContain("no figures");
    }
}
