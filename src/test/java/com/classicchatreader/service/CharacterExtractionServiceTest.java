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
}
