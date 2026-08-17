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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CharacterPortraitImageGeneratorServiceTest {

    @Mock private ComfyUIService comfyUIService;
    @Mock private XaiOAuthTokenManager oauthTokenManager;
    @Mock private ImageGenerationHttpClient httpClient;

    private CharacterPortraitImageGeneratorService service;

    @BeforeEach
    void setUp() {
        service = new CharacterPortraitImageGeneratorService(comfyUIService, oauthTokenManager, httpClient);
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
    void defaultsToComfyUiForUnknownProvider() {
        useProvider("something-else");
        assertThat(service.getProviderName()).isEqualTo("comfyui");
    }

    @Test
    void resolvesXaiProvider() {
        useProvider("XAI");
        assertThat(service.getProviderName()).isEqualTo("xai");
    }

    @Test
    void xaiIsAvailableWithOAuthAndNoApiKey() {
        useProvider("xai");
        when(oauthTokenManager.isConfigured()).thenReturn(true);

        assertThat(service.isAvailable()).isTrue();
        // The whole point of the change: a down ComfyUI must not gate the character feature.
        verify(comfyUIService, never()).isAvailable();
        // Status polls must not mint or refresh a SuperGrok token.
        verify(oauthTokenManager, never()).getAccessToken();
    }

    @Test
    void xaiIsUnavailableWithNeitherOAuthNorApiKey() {
        useProvider("xai");
        when(oauthTokenManager.isConfigured()).thenReturn(false);

        assertThat(service.isAvailable()).isFalse();
        verify(oauthTokenManager, never()).getAccessToken();
    }

    @Test
    void comfyUiAvailabilityDelegatesToComfyUi() {
        useProvider("comfyui");
        when(comfyUIService.isAvailable()).thenReturn(false);

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void xaiGenerationSavesIntoThePortraitCache() throws Exception {
        useProvider("xai");
        when(oauthTokenManager.getAccessToken()).thenReturn(Optional.of("oauth-token"));
        when(httpClient.postAndDecodePng(any(), any(), anyString(), anyString(), any(Integer.class)))
                .thenReturn(new byte[] {1, 2, 3});
        when(comfyUIService.savePortraitImage(anyString(), any())).thenReturn("cached/fortunato.png");

        String filename = service.generatePortrait("a portrait prompt", "portrait_abc", "cache-key");

        assertThat(filename).isEqualTo("cached/fortunato.png");
        verify(comfyUIService, never()).submitPortraitWorkflow(anyString(), anyString(), anyString());
    }

    @Test
    void xaiGenerationSanitizesThePromptBeforeSending() throws Exception {
        useProvider("xai");
        when(oauthTokenManager.getAccessToken()).thenReturn(Optional.of("oauth-token"));
        when(httpClient.postAndDecodePng(any(), any(), anyString(), anyString(), any(Integer.class)))
                .thenReturn(new byte[] {1, 2, 3});
        when(comfyUIService.savePortraitImage(anyString(), any())).thenReturn("cached/x.png");

        ArgumentCaptor<ObjectNode> request = ArgumentCaptor.forClass(ObjectNode.class);
        service.generatePortrait("a portrait of a character", "portrait_abc", "cache-key");

        verify(httpClient).postAndDecodePng(
                any(), request.capture(), anyString(), anyString(), any(Integer.class));
        // The classroom guardrail appends its school-safe suffix; without sanitization the
        // prompt would reach Grok Imagine exactly as the LLM wrote it.
        assertThat(request.getValue().path("prompt").asText())
                .isEqualTo(ImagePromptSafety.prepareForGeneration("a portrait of a character"));
    }

    @Test
    void xaiGenerationReplacesBlockedPromptBeforeSending() throws Exception {
        useProvider("xai");
        when(oauthTokenManager.getAccessToken()).thenReturn(Optional.of("oauth-token"));
        when(httpClient.postAndDecodePng(any(), any(), anyString(), anyString(), any(Integer.class)))
                .thenReturn(new byte[] {1, 2, 3});
        when(comfyUIService.savePortraitImage(anyString(), any())).thenReturn("cached/x.png");

        String blocked = "a nude portrait of a character";
        ArgumentCaptor<ObjectNode> request = ArgumentCaptor.forClass(ObjectNode.class);
        service.generatePortrait(blocked, "portrait_abc", "cache-key");

        verify(httpClient).postAndDecodePng(
                any(), request.capture(), anyString(), anyString(), any(Integer.class));
        assertThat(request.getValue().path("prompt").asText()).doesNotContain("nude");
    }

    @Test
    void comfyUiGenerationUsesTheWorkflow() throws Exception {
        useProvider("comfyui");
        when(comfyUIService.submitPortraitWorkflow(anyString(), anyString(), anyString())).thenReturn("prompt-1");
        when(comfyUIService.pollForPortraitCompletion("prompt-1"))
                .thenReturn(new ComfyUIService.IllustrationResult(true, "cached/montresor.png", null));

        String filename = service.generatePortrait("a portrait prompt", "portrait_abc", "cache-key");

        assertThat(filename).isEqualTo("cached/montresor.png");
    }
}
