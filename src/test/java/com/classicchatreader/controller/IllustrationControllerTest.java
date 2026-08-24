package com.classicchatreader.controller;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.ComfyUIService;
import com.classicchatreader.service.IllustrationService;
import com.classicchatreader.service.IllustrationStyleAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IllustrationController.class)
@TestPropertySource(properties = {
        "generation.cache-only=false",
        "illustration.allow-prompt-editing=true"
})
class IllustrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IllustrationController controller;

    @MockitoBean
    private IllustrationService illustrationService;

    @MockitoBean
    private IllustrationStyleAnalysisService styleAnalysisService;

    @MockitoBean
    private ComfyUIService comfyUIService;

    @MockitoBean
    private CdnAssetService cdnAssetService;

    @MockitoBean
    private BookRepository bookRepository;

    @MockitoBean
    private ChapterRepository chapterRepository;

    @Test
    void getStatus_returnsServiceFlags() throws Exception {
        when(comfyUIService.isAvailable()).thenReturn(true);
        when(styleAnalysisService.isOllamaAvailable()).thenReturn(true);
        when(illustrationService.isQueueProcessorRunning()).thenReturn(true);

        mockMvc.perform(get("/api/illustrations/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comfyuiAvailable", is(true)))
                .andExpect(jsonPath("$.ollamaAvailable", is(true)))
                .andExpect(jsonPath("$.queueProcessorRunning", is(true)))
                .andExpect(jsonPath("$.allowPromptEditing", is(true)))
                .andExpect(jsonPath("$.cacheOnly", is(false)));
    }

    @Test
    void analyzeBook_illustrationsDisabledForBook_returnsForbidden() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setId("book-1");
        book.setIllustrationEnabled(false);

        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));

        mockMvc.perform(post("/api/illustrations/analyze/book-1"))
                .andExpect(status().isForbidden());

        verify(illustrationService, never()).getOrAnalyzeBookStyle("book-1", false);
    }

    @Test
    void getIllustration_whenCdnEnabled_redirectsToAssetUrl() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        LocalDateTime completedAt = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        CdnAssetService.VersionedAsset asset =
                new CdnAssetService.VersionedAsset("chapter-1.png", completedAt);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(cdnAssetService.isEnabled()).thenReturn(true);
        when(illustrationService.getIllustrationAsset("chapter-1")).thenReturn(Optional.of(asset));
        when(cdnAssetService.buildAssetUrl("illustrations", asset))
                .thenReturn(Optional.of("https://cdn.example.com/chapter-1.png?v=1768478400-abc"));

        mockMvc.perform(get("/api/illustrations/chapter/chapter-1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://cdn.example.com/chapter-1.png?v=1768478400-abc"));
    }

    @Test
    void getChapterStatus_recoversCachedImageInCacheOnlyModeWhenDatabaseRecordIsMissing() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(illustrationService.getStatus("chapter-1")).thenReturn(null);
        when(illustrationService.restoreCachedIllustrationIfPresent("chapter-1")).thenReturn(true);

        ReflectionTestUtils.setField(controller, "cacheOnly", true);
        try {
            mockMvc.perform(get("/api/illustrations/chapter/chapter-1/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is(IllustrationStatus.COMPLETED.name())))
                    .andExpect(jsonPath("$.ready", is(true)));

            verify(illustrationService).restoreCachedIllustrationIfPresent("chapter-1");
        } finally {
            ReflectionTestUtils.setField(controller, "cacheOnly", false);
        }
    }

    @Test
    void getChapterStatus_doesNotReportReadyWhenCompletedRowCannotUseStableAsset() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(illustrationService.getStatus("chapter-1")).thenReturn(IllustrationStatus.COMPLETED);
        when(illustrationService.restoreCachedIllustrationIfPresent("chapter-1")).thenReturn(false);

        ReflectionTestUtils.setField(controller, "cacheOnly", true);
        try {
            mockMvc.perform(get("/api/illustrations/chapter/chapter-1/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("NOT_REQUESTED")))
                    .andExpect(jsonPath("$.ready", is(false)));
        } finally {
            ReflectionTestUtils.setField(controller, "cacheOnly", false);
        }
    }

    @Test
    void regenerate_blankPrompt_returnsBadRequest() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));

        mockMvc.perform(post("/api/illustrations/chapter/chapter-1/regenerate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "prompt": "   "
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(illustrationService, never()).regenerateWithPrompt("chapter-1", "   ");
    }
}
