package com.classicchatreader.service;

import com.classicchatreader.model.VoiceSettings;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceAnalysisServiceTest {

    @Test
    void analyzeBookForVoice_usesCatalogVoicesAndRejectsOpenAiIds() {
        LlmProvider provider = mock(LlmProvider.class);
        TtsService ttsService = mock(TtsService.class);
        when(provider.getProviderName()).thenReturn("xai");
        when(ttsService.currentProvider()).thenReturn("xai");
        when(ttsService.defaultVoice()).thenReturn("orion");
        when(ttsService.listVoices()).thenReturn(List.of(
                Map.of("id", "ara", "gender", "female", "description", "Warm, friendly and conversational"),
                Map.of("id", "rex", "gender", "male", "description", "Deep, calm and steady")
        ));
        when(ttsService.resolveAnalyzedVoice("fable")).thenReturn("orion");
        when(ttsService.clampSpeed(0.9)).thenReturn(0.9);
        when(provider.generate(anyString(), any(LlmOptions.class))).thenReturn("""
                {
                  "voice": "fable",
                  "speed": 0.9,
                  "instructions": "Warm and unhurried",
                  "reasoning": "Period romance"
                }
                """);

        VoiceAnalysisService service = new VoiceAnalysisService(provider, ttsService);
        VoiceSettings settings = service.analyzeBookForVoice("Pride and Prejudice", "Jane Austen", "It is a truth...");

        assertEquals("orion", settings.voice());
        assertEquals("xai", settings.provider());
        assertEquals(0.9, settings.speed());
        assertEquals("Warm and unhurried", settings.instructions());
        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(provider).generate(promptCaptor.capture(), any(LlmOptions.class));
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("- ara (female): Warm, friendly and conversational"));
        assertTrue(prompt.contains("- rex (male): Deep, calm and steady"));
        assertTrue(prompt.contains("Available voices from the current TTS provider (xai)"));
        assertTrue(prompt.contains("If no voice is a clear match, use orion."));
    }
}
