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
import com.classicchatreader.service.LiveAssetWriteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

        ReflectionTestUtils.setField(controller, "illustrationCdnEnabled", true);
        try {
            mockMvc.perform(get("/api/illustrations/chapter/chapter-1"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://cdn.example.com/chapter-1.png?v=1768478400-abc"));
        } finally {
            ReflectionTestUtils.setField(controller, "illustrationCdnEnabled", false);
        }
    }

    @Test
    void getIllustration_whenCdnUrlConfiguredButIllustrationCdnDisabled_servesLocalPng() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(cdnAssetService.isEnabled()).thenReturn(true);
        when(illustrationService.getIllustration("chapter-1")).thenReturn(png);

        mockMvc.perform(get("/api/illustrations/chapter/chapter-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().bytes(png));
    }

    @Test
    void getIllustration_whenIllustrationCdnDisabledAndLocalFileMissing_returns404WithoutRedirect() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(cdnAssetService.isEnabled()).thenReturn(true);
        when(illustrationService.getIllustration("chapter-1")).thenReturn(null);

        mockMvc.perform(get("/api/illustrations/chapter/chapter-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Location"));
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
    void uploadIllustration_studioPng_replacesLiveBytesWithoutEnqueue() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "scene.png", "image/png", png);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(illustrationService.saveUploadedIllustration(
                "chapter-1",
                png,
                "studio",
                "storm over the lake",
                null
        )).thenReturn(LiveAssetWriteResult.SAVED);
        when(illustrationService.getStatus("chapter-1")).thenReturn(IllustrationStatus.COMPLETED);
        when(illustrationService.getPrompt("chapter-1")).thenReturn("storm over the lake");

        mockMvc.perform(multipart("/api/illustrations/chapter/chapter-1")
                        .file(file)
                        .param("source", "studio")
                        .param("generated_prompt", "storm over the lake")
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.ready", is(true)))
                .andExpect(jsonPath("$.source", is("studio")))
                .andExpect(jsonPath("$.generatedPrompt", is("storm over the lake")));

        verify(illustrationService).saveUploadedIllustration(
                "chapter-1",
                png,
                "studio",
                "storm over the lake",
                null);
        verify(illustrationService, never()).requestIllustration("chapter-1");
        verify(illustrationService, never()).regenerateWithPrompt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void uploadIllustration_generating_returnsConflictWithoutEnqueue() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "scene.png", "image/png", png);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(illustrationService.saveUploadedIllustration(
                "chapter-1",
                png,
                "studio",
                "storm over the lake",
                null
        )).thenReturn(LiveAssetWriteResult.GENERATION_IN_PROGRESS);
        when(illustrationService.getStatus("chapter-1")).thenReturn(IllustrationStatus.GENERATING);

        mockMvc.perform(multipart("/api/illustrations/chapter/chapter-1")
                        .file(file)
                        .param("source", "studio")
                        .param("generated_prompt", "storm over the lake")
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is("GENERATING")));

        verify(illustrationService, never()).requestIllustration("chapter-1");
        verify(illustrationService, never()).regenerateWithPrompt(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void uploadIllustration_nonPng_returnsUnsupportedMediaType() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);
        byte[] webp = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        MockMultipartFile file = new MockMultipartFile("file", "scene.webp", "image/webp", webp);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));
        when(illustrationService.saveUploadedIllustration(
                "chapter-1",
                webp,
                null,
                null,
                null
        )).thenThrow(new com.classicchatreader.service.UnsupportedImageTypeException(
                "Illustration uploads must be PNG images."));

        mockMvc.perform(multipart("/api/illustrations/chapter/chapter-1")
                        .file(file)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isUnsupportedMediaType());

        verify(illustrationService, never()).requestIllustration("chapter-1");
    }

    @Test
    void uploadIllustration_cacheOnly_returnsConflictWithoutWrite() throws Exception {
        BookEntity book = new BookEntity("Book One", "Author One", "gutenberg");
        book.setIllustrationEnabled(true);
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "scene.png", "image/png", png);

        when(chapterRepository.findById("chapter-1")).thenReturn(Optional.of(chapter));

        ReflectionTestUtils.setField(controller, "cacheOnly", true);
        try {
            mockMvc.perform(multipart("/api/illustrations/chapter/chapter-1")
                            .file(file)
                            .param("source", "studio")
                            .with(request -> {
                                request.setMethod("PUT");
                                return request;
                            }))
                    .andExpect(status().isConflict());

            verify(illustrationService, never()).saveUploadedIllustration(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
            verify(illustrationService, never()).requestIllustration("chapter-1");
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
