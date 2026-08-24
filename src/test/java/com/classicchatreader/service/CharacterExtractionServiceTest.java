package com.classicchatreader.service;

import com.classicchatreader.service.CharacterExtractionService.ExtractedCharacter;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterExtractionServiceTest {

    @Mock
    private LlmProvider reasoningProvider;

    private CharacterExtractionService service;

    @BeforeEach
    void setUp() {
        service = new CharacterExtractionService(reasoningProvider);
        ReflectionTestUtils.setField(service, "cacheOnly", false);
        ReflectionTestUtils.setField(service, "maxCharactersPerChapter", 5);
        ReflectionTestUtils.setField(service, "maxContextChars", 24000);
    }

    @Test
    void extractCharactersFromChapter_sendsWholeChapterWhenWithinContextBudget() {
        // A single-chapter short story (The Cask of Amontillado is ~12.5k chars) must reach
        // the model intact; the old hardcoded 3000-char cut hid every late-appearing character.
        String tail = "For the love of God, Montresor!";
        String chapterContent = "The thousand injuries of Fortunato I had borne as I best could. "
                + "x".repeat(12000) + tail;

        when(reasoningProvider.generate(any(), any())).thenReturn("[]");

        service.extractCharactersFromChapter(
                "The Cask of Amontillado", "Edgar Allan Poe", "Chapter 1", chapterContent, List.of());

        verify(reasoningProvider).generate(contains(tail), any());
    }

    @Test
    void extractCharactersFromChapter_truncatesChapterBeyondContextBudget() {
        ReflectionTestUtils.setField(service, "maxContextChars", 500);
        String tail = "Luchesi cannot tell Amontillado from Sherry.";
        String chapterContent = "y".repeat(600) + tail;

        when(reasoningProvider.generate(any(), any())).thenReturn("[]");

        service.extractCharactersFromChapter(
                "The Cask of Amontillado", "Edgar Allan Poe", "Chapter 1", chapterContent, List.of());

        verify(reasoningProvider).generate(argThat(prompt -> !prompt.contains(tail)), any());
    }

    @Test
    void extractCharactersFromChapter_repairsMalformedJsonOnce() {
        when(reasoningProvider.generate(any(), any()))
                .thenReturn("""
                        [
                          {
                            "name": "Herbert Pocket"
                            "description": "Pip's friend (kind and loyal)",
                            "approximateParagraphIndex": 4
                          }
                        ]
                        """)
                .thenReturn("""
                        [
                          {
                            "name": "Herbert Pocket",
                            "description": "Pip's friend (kind and loyal)",
                            "approximateParagraphIndex": 4
                          }
                        ]
                        """);

        List<ExtractedCharacter> result = service.extractCharactersFromChapter(
                "Great Expectations",
                "Charles Dickens",
                "Chapter XXXVII.",
                "Some chapter text",
                List.of("Pip")
        );

        assertEquals(1, result.size());
        assertEquals("Herbert Pocket", result.get(0).name());
        verify(reasoningProvider, times(2)).generate(any(), any());
    }

    @Test
    void extractCharactersFromChapter_throwsWhenRepairStillInvalid() {
        when(reasoningProvider.generate(any(), any()))
                .thenReturn("[{\"name\":\"Herbert\" \"description\":\"Broken\"}]")
                .thenReturn("[{\"name\":\"Herbert\" \"description\":\"Still broken\"}]");

        assertThrows(IllegalStateException.class, () -> service.extractCharactersFromChapter(
                "Great Expectations",
                "Charles Dickens",
                "Chapter XXXVII.",
                "Some chapter text",
                List.of()
        ));

        verify(reasoningProvider).generate(contains("Convert the following malformed model output"), any());
    }

    @Test
    void extractionPrompt_requiresNamedPeopleAndFirstAppearanceBlurbs() {
        String prompt = service.buildExtractionPrompt(
                "Frankenstein",
                "Mary Wollstonecraft Shelley",
                "Letter I",
                "Some chapter text",
                "(none yet)"
        );

        assertTrue(prompt.contains(CharacterDiscoveryPromptRules.NAMED_PEOPLE_ONLY));
        assertTrue(prompt.contains(CharacterDiscoveryPromptRules.REJECT_NON_PERSONS));
        assertTrue(prompt.contains(CharacterDiscoveryPromptRules.NO_GLITCH_NAMES));
        assertTrue(prompt.contains(CharacterDiscoveryPromptRules.FIRST_APPEARANCE_BLURB));
        assertTrue(prompt.contains("SECONDARY set sane"));
    }

    @Test
    void extractCharactersFromChapter_dropsJunkNamesAndKeepsNamedPeople() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name":"bees","description":"insects","approximateParagraphIndex":0},
                  {"name":"The Moon","description":"celestial","approximateParagraphIndex":1},
                  {"name":"The Mule","description":"animal","approximateParagraphIndex":2},
                  {"name":"The Monster","description":"the being villagers fear","approximateParagraphIndex":3},
                  {"name":"The Creature","description":"Victor's unnamed creation","approximateParagraphIndex":4},
                  {"name":"The Turk","description":"a prize swordsman","approximateParagraphIndex":5},
                  {"name":"maid","description":"a household servant","approximateParagraphIndex":6},
                  {"name":"Elizabeth Lavenza (again)","description":"duplicate leftover","approximateParagraphIndex":7}
                ]
                """);

        List<ExtractedCharacter> result = service.extractCharactersFromChapter(
                "Mixed Cast",
                "Various",
                "Chapter 1",
                "Some chapter text",
                List.of()
        );

        assertEquals(3, result.size());
        assertEquals("The Monster", result.get(0).name());
        assertEquals("The Creature", result.get(1).name());
        assertEquals("The Turk", result.get(2).name());
    }
}
