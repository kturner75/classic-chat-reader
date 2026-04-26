package com.classicchatreader.controller;

import com.classicchatreader.model.Book;
import com.classicchatreader.model.BookmarkedParagraph;
import com.classicchatreader.model.ChapterContent;
import com.classicchatreader.model.ParagraphAnnotation;
import com.classicchatreader.model.Paragraph;
import com.classicchatreader.service.BookCoverService;
import com.classicchatreader.service.BookStorageService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.ParagraphAnnotationService;
import com.classicchatreader.service.ReaderIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LibraryController.class)
class LibraryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookStorageService bookStorageService;

    @MockitoBean
    private BookCoverService bookCoverService;

    @MockitoBean
    private CdnAssetService cdnAssetService;

    @MockitoBean
    private ParagraphAnnotationService paragraphAnnotationService;

    @MockitoBean
    private ReaderIdentityService readerIdentityService;

    @Test
    void listBooks_returnsAllBooks() throws Exception {
        List<Book> books = List.of(
            new Book("book-1", "Pride and Prejudice", "Jane Austen", "A romantic novel.", null, List.of(), false, false, false),
            new Book("book-2", "Moby Dick", "Herman Melville", "A whale tale.", null, List.of(), false, false, false)
        );
        when(bookStorageService.getAllBooks()).thenReturn(books);

        mockMvc.perform(get("/api/library"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].id", is("book-1")))
            .andExpect(jsonPath("$[0].title", is("Pride and Prejudice")))
            .andExpect(jsonPath("$[0].author", is("Jane Austen")))
            .andExpect(jsonPath("$[1].id", is("book-2")))
            .andExpect(jsonPath("$[1].title", is("Moby Dick")));
    }

    @Test
    void listBooks_includesChapters() throws Exception {
        List<Book.Chapter> chapters = List.of(
            new Book.Chapter("ch-1", "Chapter 1"),
            new Book.Chapter("ch-2", "Chapter 2"),
            new Book.Chapter("ch-3", "Chapter 3")
        );
        List<Book> books = List.of(
            new Book("book-1", "Pride and Prejudice", "Jane Austen", null, null, chapters, false, false, false)
        );
        when(bookStorageService.getAllBooks()).thenReturn(books);

        mockMvc.perform(get("/api/library"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].chapters", hasSize(3)))
            .andExpect(jsonPath("$[0].chapters[0].id", is("ch-1")))
            .andExpect(jsonPath("$[0].chapters[0].title", is("Chapter 1")));
    }

    @Test
    void getBook_existingBook_returnsBook() throws Exception {
        Book book = new Book("book-1", "Pride and Prejudice", "Jane Austen",
            "A romantic novel following the Bennet family.", null, List.of(), false, false, false);
        when(bookStorageService.getBook("book-1")).thenReturn(Optional.of(book));

        mockMvc.perform(get("/api/library/book-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id", is("book-1")))
            .andExpect(jsonPath("$.title", is("Pride and Prejudice")))
            .andExpect(jsonPath("$.author", is("Jane Austen")))
            .andExpect(jsonPath("$.description", is("A romantic novel following the Bennet family.")));
    }

    @Test
    void getBook_nonExistingBook_returns404() throws Exception {
        when(bookStorageService.getBook("unknown-book")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/library/unknown-book"))
            .andExpect(status().isNotFound());
    }

    @Test
    void retryBookCover_withPrompt_queuesRetry() throws Exception {
        Book book = new Book("book-1", "Pride and Prejudice", "Jane Austen", null, null, List.of(), false, false, false);
        when(bookStorageService.getBook("book-1")).thenReturn(Optional.of(book));
        when(bookCoverService.retryCover("book-1", "ornate regency ballroom, no text")).thenReturn(true);
        when(bookCoverService.getCoverStatus("book-1")).thenReturn(Optional.of(new BookCoverService.CoverStatus(
                "book-1",
                com.classicchatreader.entity.IllustrationStatus.PENDING,
                "books/gutenberg/1342/covers/cover.png",
                "ornate regency ballroom, no text",
                "ornate regency ballroom, no text",
                "prompt_override",
                null,
                null
        )));

        mockMvc.perform(post("/api/library/book-1/cover/retry")
                        .contentType("application/json")
                        .content("{\"prompt\":\"ornate regency ballroom, no text\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.hasImage", is(true)))
                .andExpect(jsonPath("$.coverUrl", is("/api/library/book-1/cover")))
                .andExpect(jsonPath("$.promptOverride", is("ornate regency ballroom, no text")));

        verify(bookCoverService).retryCover("book-1", "ornate regency ballroom, no text");
    }

    @Test
    void uploadBookCover_savesManualPng() throws Exception {
        Book book = new Book("book-1", "Pride and Prejudice", "Jane Austen", null, null, List.of(), false, false, false);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", png);
        when(bookStorageService.getBook("book-1")).thenReturn(Optional.of(book));
        when(bookCoverService.saveManualCover("book-1", png)).thenReturn(true);
        when(bookCoverService.getCoverStatus("book-1")).thenReturn(Optional.of(new BookCoverService.CoverStatus(
                "book-1",
                com.classicchatreader.entity.IllustrationStatus.COMPLETED,
                "books/gutenberg/1342/covers/cover.png",
                null,
                null,
                "manual_upload",
                null,
                null
        )));

        mockMvc.perform(multipart("/api/library/book-1/cover")
                        .file(file)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.ready", is(true)))
                .andExpect(jsonPath("$.coverSource", is("manual_upload")));

        verify(bookCoverService).saveManualCover("book-1", png);
    }

    @Test
    void getMlaCitation_existingBook_returnsCitation() throws Exception {
        when(bookStorageService.getMlaCitation(eq("book-1"), anyString(), isNull(), isNull()))
                .thenReturn(Optional.of("Austen, Jane. Pride and Prejudice."));

        mockMvc.perform(get("/api/library/book-1/citation/mla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citation", is("Austen, Jane. Pride and Prejudice.")));
    }

    @Test
    void getMlaCitation_missingBook_returns404() throws Exception {
        when(bookStorageService.getMlaCitation(eq("missing-book"), anyString(), isNull(), isNull())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/library/missing-book/citation/mla"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMlaCitation_withParagraphContext_passesLocatorToService() throws Exception {
        when(bookStorageService.getMlaCitation(eq("book-1"), anyString(), eq("ch-1"), eq(3)))
                .thenReturn(Optional.of("Austen, Jane. Pride and Prejudice. Classic Chat Reader."));

        mockMvc.perform(get("/api/library/book-1/citation/mla")
                        .param("chapterId", "ch-1")
                        .param("paragraphIndex", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citation", is("Austen, Jane. Pride and Prejudice. Classic Chat Reader.")));
    }

    @Test
    void getChapterContent_existingChapter_returnsContent() throws Exception {
        List<Paragraph> paragraphs = List.of(
            new Paragraph(0, "It is a truth universally acknowledged..."),
            new Paragraph(1, "However little known...")
        );
        ChapterContent content = new ChapterContent("book-1", "ch-1", "Chapter 1", paragraphs);
        when(bookStorageService.getChapterContent("book-1", "ch-1")).thenReturn(Optional.of(content));

        mockMvc.perform(get("/api/library/book-1/chapters/ch-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bookId", is("book-1")))
            .andExpect(jsonPath("$.chapterId", is("ch-1")))
            .andExpect(jsonPath("$.title", is("Chapter 1")))
            .andExpect(jsonPath("$.paragraphs", hasSize(2)))
            .andExpect(jsonPath("$.paragraphs[0].index", is(0)))
            .andExpect(jsonPath("$.paragraphs[0].content", containsString("truth universally acknowledged")));
    }

    @Test
    void getChapterContent_existingChapter_returnsParagraphsInOrder() throws Exception {
        List<Paragraph> paragraphs = List.of(
            new Paragraph(0, "Call me Ishmael."),
            new Paragraph(1, "It is a way I have of driving off the spleen.")
        );
        ChapterContent content = new ChapterContent("book-2", "ch-1", "Loomings", paragraphs);
        when(bookStorageService.getChapterContent("book-2", "ch-1")).thenReturn(Optional.of(content));

        mockMvc.perform(get("/api/library/book-2/chapters/ch-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paragraphs[0].index", is(0)))
            .andExpect(jsonPath("$.paragraphs[0].content", containsString("Call me Ishmael")))
            .andExpect(jsonPath("$.paragraphs[1].index", is(1)))
            .andExpect(jsonPath("$.paragraphs[1].content", containsString("driving off the spleen")));
    }

    @Test
    void getChapterContent_nonExistingBook_returns404() throws Exception {
        when(bookStorageService.getChapterContent("unknown-book", "ch-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/library/unknown-book/chapters/ch-1"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getChapterContent_nonExistingChapter_returns404() throws Exception {
        when(bookStorageService.getChapterContent("book-1", "ch-99")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/library/book-1/chapters/ch-99"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getChapterContent_chapterWithoutContent_returnsEmptyParagraphs() throws Exception {
        ChapterContent content = new ChapterContent("book-1", "ch-2", "Chapter 2", List.of());
        when(bookStorageService.getChapterContent("book-1", "ch-2")).thenReturn(Optional.of(content));

        mockMvc.perform(get("/api/library/book-1/chapters/ch-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paragraphs", hasSize(0)));
    }

    @Test
    void updateBookFeatures_updatesFlags() throws Exception {
        Book updated = new Book(
            "book-1",
            "Pride and Prejudice",
            "Jane Austen",
            "A romantic novel following the Bennet family.",
            null,
            List.of(),
            true,
            false,
            true
        );
        when(bookStorageService.updateBookFeatures("book-1", true, false, true))
            .thenReturn(Optional.of(updated));

        mockMvc.perform(patch("/api/library/book-1/features")
                .contentType("application/json")
                .content("{\"ttsEnabled\":true,\"illustrationEnabled\":false,\"characterEnabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id", is("book-1")))
            .andExpect(jsonPath("$.ttsEnabled", is(true)))
            .andExpect(jsonPath("$.illustrationEnabled", is(false)))
            .andExpect(jsonPath("$.characterEnabled", is(true)));
    }

    @Test
    void getAnnotations_returnsReaderAnnotations() throws Exception {
        List<ParagraphAnnotation> annotations = List.of(
                new ParagraphAnnotation("ch-1", 2, true, "Important line", false, null)
        );
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(paragraphAnnotationService.getBookAnnotations("reader-1", null, "book-1"))
                .thenReturn(Optional.of(annotations));

        mockMvc.perform(get("/api/library/book-1/annotations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].chapterId", is("ch-1")))
                .andExpect(jsonPath("$[0].paragraphIndex", is(2)))
                .andExpect(jsonPath("$[0].highlighted", is(true)))
                .andExpect(jsonPath("$[0].noteText", is("Important line")))
                .andExpect(jsonPath("$[0].bookmarked", is(false)));
    }

    @Test
    void getAnnotations_withAccountIdentity_usesUserScopedReaderKey() throws Exception {
        List<ParagraphAnnotation> annotations = List.of(
                new ParagraphAnnotation("ch-1", 1, false, null, true, null)
        );
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("user:user-42", true, "user-42"));
        when(paragraphAnnotationService.getBookAnnotations("user:user-42", "user-42", "book-1"))
                .thenReturn(Optional.of(annotations));

        mockMvc.perform(get("/api/library/book-1/annotations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookmarked", is(true)));
    }

    @Test
    void getBookmarks_returnsReaderBookmarks() throws Exception {
        List<BookmarkedParagraph> bookmarks = List.of(
                new BookmarkedParagraph("ch-3", "Chapter 3", 7, "A short snippet.", null)
        );
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(paragraphAnnotationService.getBookmarkedParagraphs("reader-1", null, "book-1"))
                .thenReturn(Optional.of(bookmarks));

        mockMvc.perform(get("/api/library/book-1/bookmarks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].chapterId", is("ch-3")))
                .andExpect(jsonPath("$[0].chapterTitle", is("Chapter 3")))
                .andExpect(jsonPath("$[0].paragraphIndex", is(7)))
                .andExpect(jsonPath("$[0].snippet", is("A short snippet.")));
    }

    @Test
    void upsertAnnotation_returnsSavedAnnotation() throws Exception {
        ParagraphAnnotation saved = new ParagraphAnnotation("ch-1", 4, true, "Saved note", true, null);
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(paragraphAnnotationService.saveAnnotation("reader-1", null, "book-1", "ch-1", 4, true, "Saved note", true))
                .thenReturn(new ParagraphAnnotationService.SaveOutcome(
                        ParagraphAnnotationService.SaveStatus.SAVED,
                        saved
                ));

        mockMvc.perform(put("/api/library/book-1/annotations/ch-1/4")
                        .contentType("application/json")
                        .content("{\"highlighted\":true,\"noteText\":\"Saved note\",\"bookmarked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterId", is("ch-1")))
                .andExpect(jsonPath("$.paragraphIndex", is(4)))
                .andExpect(jsonPath("$.highlighted", is(true)))
                .andExpect(jsonPath("$.noteText", is("Saved note")))
                .andExpect(jsonPath("$.bookmarked", is(true)));
    }

    @Test
    void upsertAnnotation_whenCleared_returnsNoContent() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(paragraphAnnotationService.saveAnnotation("reader-1", null, "book-1", "ch-1", 4, false, "", false))
                .thenReturn(new ParagraphAnnotationService.SaveOutcome(
                        ParagraphAnnotationService.SaveStatus.CLEARED,
                        null
                ));

        mockMvc.perform(put("/api/library/book-1/annotations/ch-1/4")
                        .contentType("application/json")
                        .content("{\"highlighted\":false,\"noteText\":\"\",\"bookmarked\":false}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAnnotation_returnsNoContent() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(paragraphAnnotationService.deleteAnnotation("reader-1", null, "book-1", "ch-1", 2))
                .thenReturn(ParagraphAnnotationService.DeleteStatus.DELETED);

        mockMvc.perform(delete("/api/library/book-1/annotations/ch-1/2"))
                .andExpect(status().isNoContent());
    }
}
