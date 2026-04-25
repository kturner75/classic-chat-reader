package com.classicchatreader.smoke;

import com.classicchatreader.config.DataInitializer;
import com.classicchatreader.config.SearchIndexInitializer;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.service.ComfyUIService;
import com.classicchatreader.service.PreGenerationService;
import com.classicchatreader.service.PreGenerationService.PreGenResult;
import com.classicchatreader.service.llm.LlmOptions;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
class AiMediaPipelinesSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookRepository bookRepository;

    @MockitoBean
    @Qualifier("reasoningLlmProvider")
    private LlmProvider reasoningLlmProvider;

    @MockitoBean
    private ComfyUIService comfyUIService;

    @MockitoBean
    private PreGenerationService preGenerationService;

    @MockitoBean
    private DataInitializer dataInitializer;

    @MockitoBean
    private SearchIndexInitializer searchIndexInitializer;

    private String bookId;

    @BeforeEach
    void setUp() {
        bookRepository.deleteAll();

        BookEntity book = new BookEntity("Smoke Pipeline Book", "Smoke Author", "manual");
        book.setTtsEnabled(true);
        book.setIllustrationEnabled(true);
        book.setCharacterEnabled(true);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.addParagraph(new ParagraphEntity(0, "<p>It was a bright cold day in April.</p>"));
        book.addChapter(chapter);

        BookEntity saved = bookRepository.save(book);
        bookId = saved.getId();

        when(reasoningLlmProvider.getProviderName()).thenReturn("smoke-mock");
        when(reasoningLlmProvider.isAvailable()).thenReturn(true);
        when(reasoningLlmProvider.generate(anyString(), any(LlmOptions.class))).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0, String.class);
            if (prompt.contains("text-to-speech voice")) {
                return """
                        {
                          "voice": "fable",
                          "speed": 0.95,
                          "instructions": "Warm and clear",
                          "reasoning": "Fits the narrative tone"
                        }
                        """;
            }
            if (prompt.contains("illustration style")) {
                return """
                        {
                          "style": "pen-and-ink",
                          "promptPrefix": "detailed pen and ink illustration,",
                          "setting": "Victorian England",
                          "reasoning": "Classic narrative fit"
                        }
                        """;
            }
            return "[]";
        });

        when(comfyUIService.isAvailable()).thenReturn(true);
        when(preGenerationService.preGenerateForBook(bookId)).thenReturn(new PreGenResult(
                true,
                bookId,
                "Smoke Pipeline Book",
                "Smoke pre-generation complete",
                1,
                1,
                0,
                0,
                0,
                1,
                0,
                1,
                0,
                1
        ));
    }

    @Test
    void smokeProfile_happyPath_endToEndWithMockedProviders() throws Exception {
        mockMvc.perform(post("/api/tts/analyze/{bookId}", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voice", is("fable")))
                .andExpect(jsonPath("$.speed", is(0.95)));

        mockMvc.perform(post("/api/illustrations/analyze/{bookId}", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style", is("pen-and-ink")))
                .andExpect(jsonPath("$.setting", is("Victorian England")));

        mockMvc.perform(get("/api/characters/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasoningProviderAvailable", is(true)))
                .andExpect(jsonPath("$.comfyuiAvailable", is(true)));

        mockMvc.perform(post("/api/pregen/book/{bookId}", bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.bookId", is(bookId)));

        BookEntity updated = bookRepository.findById(bookId).orElseThrow();
        assertEquals("fable", updated.getTtsVoice());
        assertEquals("pen-and-ink", updated.getIllustrationStyle());
        assertTrue(updated.getTtsSpeed() > 0.0);

        verify(preGenerationService).preGenerateForBook(bookId);
    }
}
