package com.classicchatreader.service;

import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import com.classicchatreader.model.IllustrationStyleSuggestions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IllustrationStyleAnalysisServiceTest {

    @Test
    void stylePromptPrefersColorForChildrensBooks() {
        AtomicReference<String> sent = new AtomicReference<>("");
        LlmProvider reasoning = mock(LlmProvider.class);
        when(reasoning.getProviderName()).thenReturn("test");
        when(reasoning.generate(any(), any())).thenAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return """
                    {
                      "style": "watercolor",
                      "coverSubject": "character",
                      "coverFocus": "Polly in a Boston parlor",
                      "promptPrefix": "warm watercolor, vintage children's book illustration, soft color,",
                      "setting": "1860s Boston and New England",
                      "reasoning": "Juvenile Alcott novel"
                    }
                    """;
        });

        IllustrationStyleAnalysisService service = new IllustrationStyleAnalysisService(reasoning);
        service.analyzeBookForStyle(
                "An Old-Fashioned Girl",
                "Louisa May Alcott",
                "Polly sat by the window.");

        String prompt = sent.get();
        assertTrue(prompt.contains("Children's and juvenile books must use color"));
        assertTrue(prompt.contains("Alcott"));
        assertFalse(prompt.contains("Best for: Victorian fiction, mysteries, adventures (Dickens, Conan Doyle, Stevenson)"));
        assertTrue(prompt.contains("must not say \"black and white\""));
    }

    @Test
    void suggestStylesAsksForDistinctFitsAndParsesUpToLimit() {
        AtomicReference<String> sent = new AtomicReference<>("");
        LlmProvider reasoning = mock(LlmProvider.class);
        when(reasoning.getProviderName()).thenReturn("test");
        when(reasoning.generate(any(), any())).thenAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return """
                    {
                      "setting": "1860s Boston",
                      "suggestions": [
                        {
                          "style": "watercolor",
                          "label": "Warm watercolor",
                          "promptPrefix": "warm watercolor, vintage children's book illustration, soft color,",
                          "reasoning": "Juvenile Alcott novel"
                        },
                        {
                          "style": "art-nouveau",
                          "label": "Decorative nouveau",
                          "promptPrefix": "art nouveau, flowing parlor fashion plates,",
                          "reasoning": "Period dress"
                        },
                        {
                          "style": "oil-painting",
                          "label": "Parlor oil",
                          "promptPrefix": "oil painting, warm domestic interior,",
                          "reasoning": "Too many"
                        }
                      ]
                    }
                    """;
        });

        IllustrationStyleAnalysisService service = new IllustrationStyleAnalysisService(reasoning);
        IllustrationStyleSuggestions out = service.suggestStylesForBook(
                "An Old-Fashioned Girl",
                "Louisa May Alcott",
                "Polly sat by the window.",
                2);

        String prompt = sent.get();
        assertTrue(prompt.contains("suggest up to 2 DISTINCT"));
        assertTrue(prompt.contains("Children's and juvenile books must use color"));
        assertEquals(2, out.suggestions().size());
        assertEquals("watercolor", out.suggestions().get(0).style());
        assertEquals("Warm watercolor", out.suggestions().get(0).label());
        assertEquals("1860s Boston", out.setting());
    }

    @Test
    void clampSuggestionLimitDefaultsAndCaps() {
        assertEquals(4, IllustrationStyleAnalysisService.clampSuggestionLimit(0));
        assertEquals(3, IllustrationStyleAnalysisService.clampSuggestionLimit(3));
        assertEquals(5, IllustrationStyleAnalysisService.clampSuggestionLimit(9));
    }
}
