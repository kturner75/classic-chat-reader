package com.classicchatreader.service;

import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IllustrationPromptServiceTest {

    @Test
    void chapterPromptAsksForVisibleFacesNotSilhouettes() {
        AtomicReference<String> llmPrompt = new AtomicReference<>("");
        LlmProvider reasoning = mock(LlmProvider.class);
        when(reasoning.getProviderName()).thenReturn("test");
        when(reasoning.generate(any(), any())).thenAnswer(invocation -> {
            llmPrompt.set(invocation.getArgument(0));
            return "vintage book illustration of Polly in a Boston parlor, visible face, afternoon light";
        });

        IllustrationPromptService service = new IllustrationPromptService(reasoning);
        String result = service.generatePromptForChapter(
                "An Old-Fashioned Girl",
                "Louisa May Alcott",
                "CHAPTER I. POLLY ARRIVES",
                "Polly sat by the window and looked out at the city.",
                IllustrationSettings.defaults(),
                java.util.List.of("Polly Milton"));

        String sent = llmPrompt.get();
        assertFalse(sent.contains("DO NOT include human faces"));
        assertFalse(sent.contains("use silhouettes, back views, or distant figures"));
        assertFalse(sent.contains("CLASSROOM IMAGE SAFETY"));
        assertFalse(sent.contains("Prefer setting, objects, atmosphere"));
        assertTrue(sent.contains("visible faces"));
        assertTrue(sent.contains("not silhouettes"));
        assertTrue(sent.contains("CAST"));
        assertTrue(sent.contains("Polly Milton"));
        assertTrue(sent.contains("Use the CAST names exactly"));
        assertTrue(sent.contains("not a character portrait"));
        assertTrue(sent.contains("CHAPTER ILLUSTRATION PLATE"));
        assertTrue(sent.contains("Keep that exact medium"));
        assertTrue(sent.contains("Do not switch to cartoon"));
        assertTrue(sent.contains("children's watercolor"));
        assertTrue(result.contains("Polly in a Boston parlor"));
        assertTrue(result.startsWith(IllustrationSettings.defaults().promptPrefix().trim()));
        assertTrue(result.contains("same medium as the rest of this book"));
        assertTrue(result.contains("not a cartoon, not a children's watercolor"));
        assertFalse(result.contains("School-appropriate book illustration"));
        assertFalse(result.contains("distant architecture and landscape only"));
        assertFalse(result.contains("no figures"));
    }

    @Test
    void prependsPrefixAndLocksMediumWhenLlmOmitsThem() {
        LlmProvider reasoning = mock(LlmProvider.class);
        when(reasoning.getProviderName()).thenReturn("test");
        when(reasoning.generate(any(), any())).thenReturn(
                "Polly Milton in a Boston parlor, visible face, afternoon light");

        IllustrationSettings painterly = new IllustrationSettings(
                "painterly oil illustration",
                "painterly oil-painting book illustration, naturalistic figures and light, fine brushwork,",
                null,
                "oil soak",
                null,
                null);
        IllustrationPromptService service = new IllustrationPromptService(reasoning);
        String result = service.generatePromptForChapter(
                "An Old-Fashioned Girl",
                "Louisa May Alcott",
                "CHAPTER I. POLLY ARRIVES",
                "Polly sat by the window.",
                painterly,
                java.util.List.of("Polly Milton"));

        assertTrue(result.startsWith(painterly.promptPrefix().trim()));
        assertTrue(result.contains("Polly Milton in a Boston parlor"));
        assertTrue(result.contains("painterly oil illustration"));
        assertTrue(result.endsWith(
                "same medium as the rest of this book, not a cartoon, not a children's watercolor."));
        assertFalse(result.contains("School-appropriate book illustration"));
        assertFalse(result.contains("distant architecture and landscape only"));
    }

    @Test
    void ensureNarrativePlateDoesNotDuplicate() {
        String once = IllustrationPromptService.ensureNarrativePlate("Polly at the window");
        assertTrue(once.contains("not a character portrait"));
        assertEquals(once, IllustrationPromptService.ensureNarrativePlate(once));
    }

    @Test
    void detectsOldSilhouettePromptsButKeepsNewFaceGuidance() {
        assertTrue(IllustrationPromptService.isSilhouetteEraPrompt(
                "DO NOT include human faces or detailed character features"));
        assertTrue(IllustrationPromptService.isSilhouetteEraPrompt(
                "Polly in silhouette standing in a Boston parlor"));
        assertFalse(IllustrationPromptService.isSilhouetteEraPrompt(
                "Polly in a Boston parlor, visible face, not silhouettes"));
        assertFalse(IllustrationPromptService.isSilhouetteEraPrompt(
                "vintage book illustration of a parlor afternoon"));
    }
}
