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
    void extractCharactersFromChapter_dedupesNormalizedNamesInOneResponse() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name": "Sally", "description": "Catherine's sister", "approximateParagraphIndex": 1},
                  {"name": "Sally.", "description": "The same sister", "approximateParagraphIndex": 4},
                  {"name": "  SALLY  ", "description": "Again", "approximateParagraphIndex": 8}
                ]
                """);

        List<ExtractedCharacter> result = service.extractCharactersFromChapter(
                "Northanger Abbey",
                "Jane Austen",
                "Chapter 1",
                "Sally was a good-humoured girl.",
                List.of()
        );

        assertEquals(1, result.size());
        assertEquals("Sally", result.get(0).name());
    }

    @Test
    void extractCharactersFromChapter_skipsVariantsAlreadyKnown() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name": "sally", "description": "Already known", "approximateParagraphIndex": 2}
                ]
                """);

        List<ExtractedCharacter> result = service.extractCharactersFromChapter(
                "Northanger Abbey",
                "Jane Austen",
                "Chapter 5",
                "Sally smiled.",
                List.of("Sally")
        );

        assertEquals(0, result.size());
    }

    @Test
    void extractCharactersFromChapter_keepsDistinctPeopleWhoShareASurnameOrTitle() {
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                [
                  {"name": "Elizabeth Bennet", "description": "The second daughter", "approximateParagraphIndex": 2},
                  {"name": "Mrs. Allen", "description": "A chaperone", "approximateParagraphIndex": 4}
                ]
                """);

        List<ExtractedCharacter> result = service.extractCharactersFromChapter(
                "Pride and Prejudice",
                "Jane Austen",
                "Chapter 1",
                "Mrs. Bennet spoke. Mr. Allen arrived later.",
                List.of("Mrs. Bennet", "Mr. Allen")
        );

        assertEquals(2, result.size());
        assertEquals("Elizabeth Bennet", result.get(0).name());
        assertEquals("Mrs. Allen", result.get(1).name());
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
}
