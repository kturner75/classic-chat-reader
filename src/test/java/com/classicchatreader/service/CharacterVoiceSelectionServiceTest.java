package com.classicchatreader.service;

import com.classicchatreader.service.CharacterVoiceSelectionService.VoiceSelection;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import com.classicchatreader.service.llm.XaiVoiceCatalogService;
import com.classicchatreader.service.llm.XaiVoiceCatalogService.XaiVoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterVoiceSelectionServiceTest {

    private static final String NAME = "Elizabeth Bennet";
    private static final String DESCRIPTION =
            "She is a witty and spirited young woman; her sisters admire her lively mind.";

    private static final List<XaiVoice> ROSTER = List.of(
            new XaiVoice("ara", "female", "Warm and friendly"),
            new XaiVoice("luna", "female", "Soft and dreamy"),
            new XaiVoice("atlas", "male", "Grounded and reassuring"));

    @Mock
    private LlmProvider reasoningProvider;

    @Mock
    private XaiVoiceCatalogService voiceCatalog;

    private CharacterVoiceSelectionService service;

    @BeforeEach
    void setUp() {
        service = new CharacterVoiceSelectionService(
                reasoningProvider, voiceCatalog, new CharacterVoiceAssigner());
    }

    @Test
    void selectVoice_llmPick_returnsRosterVoiceWithReasoning() {
        when(voiceCatalog.getVoices()).thenReturn(ROSTER);
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("{\"voice\":\"luna\",\"reasoning\":\"Soft voice suits her\"}");

        VoiceSelection selection = service.selectVoice(NAME, DESCRIPTION);

        assertEquals("luna", selection.voice());
        assertEquals("Soft voice suits her", selection.reasoning());
        assertTrue(selection.fromLlm());
        // Prompt should carry the roster with descriptions and the character description.
        verify(reasoningProvider).generate(contains("- luna (female): Soft and dreamy"), any(LlmOptions.class));
        verify(reasoningProvider).generate(contains(DESCRIPTION), any(LlmOptions.class));
    }

    @Test
    void selectVoice_normalizesCaseAndSurroundingText() {
        when(voiceCatalog.getVoices()).thenReturn(ROSTER);
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("Sure! Here you go: {\"voice\":\"Luna\",\"reasoning\":\"fits\"} Hope that helps.");

        VoiceSelection selection = service.selectVoice(NAME, DESCRIPTION);

        assertEquals("luna", selection.voice());
        assertTrue(selection.fromLlm());
    }

    @Test
    void selectVoice_offRosterVoice_fallsBackToHeuristic() {
        when(voiceCatalog.getVoices()).thenReturn(ROSTER);
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("{\"voice\":\"nonexistent\",\"reasoning\":\"made up\"}");

        VoiceSelection selection = service.selectVoice(NAME, DESCRIPTION);

        assertFalse(selection.fromLlm());
        assertTrue(List.of("eve", "ara").contains(selection.voice()),
                "female description should map to a female heuristic voice");
    }

    @Test
    void selectVoice_llmThrows_fallsBackToHeuristic() {
        when(voiceCatalog.getVoices()).thenReturn(ROSTER);
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.generate(anyString(), any(LlmOptions.class)))
                .thenThrow(new RuntimeException("provider exploded"));

        VoiceSelection selection = service.selectVoice(NAME, DESCRIPTION);

        assertFalse(selection.fromLlm());
    }

    @Test
    void selectVoice_garbageJson_fallsBackToHeuristic() {
        when(voiceCatalog.getVoices()).thenReturn(ROSTER);
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.generate(anyString(), any(LlmOptions.class)))
                .thenReturn("I think ara would be lovely for her.");

        VoiceSelection selection = service.selectVoice(NAME, DESCRIPTION);

        assertFalse(selection.fromLlm());
    }

    @Test
    void selectVoice_providerUnavailable_skipsLlmEntirely() {
        when(reasoningProvider.isAvailable()).thenReturn(false);

        VoiceSelection selection = service.selectVoice(NAME, DESCRIPTION);

        assertFalse(selection.fromLlm());
        verify(reasoningProvider, never()).generate(anyString(), any(LlmOptions.class));
    }

    @Test
    void selectVoice_emptyRoster_skipsLlmEntirely() {
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(voiceCatalog.getVoices()).thenReturn(List.of());

        VoiceSelection selection = service.selectVoice(NAME, DESCRIPTION);

        assertFalse(selection.fromLlm());
        verify(reasoningProvider, never()).generate(anyString(), any(LlmOptions.class));
    }

    @Test
    void selectVoice_cacheOnlyMode_skipsLlmEntirely() {
        ReflectionTestUtils.setField(service, "cacheOnly", true);

        VoiceSelection selection = service.selectVoice(NAME, DESCRIPTION);

        assertFalse(selection.fromLlm());
        verify(reasoningProvider, never()).generate(anyString(), any(LlmOptions.class));
    }
}
