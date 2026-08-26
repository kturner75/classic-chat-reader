package com.classicchatreader.controller;

import com.classicchatreader.model.Book;
import com.classicchatreader.service.BookCoverService;
import com.classicchatreader.service.BookStorageService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.ParagraphAnnotationService;
import com.classicchatreader.service.ReaderIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LibraryController.class)
@TestPropertySource(properties = "generation.cache-only=true")
class LibraryControllerCacheOnlyTest {

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
    void uploadBookCover_cacheOnly_returnsConflictWithoutWrite() throws Exception {
        Book book = new Book("book-1", "Pride and Prejudice", "Jane Austen", null, null, List.of(), false, false, false);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", png);
        when(bookStorageService.getBook("book-1")).thenReturn(Optional.of(book));
        when(bookCoverService.getCoverStatus("book-1")).thenReturn(Optional.empty());

        mockMvc.perform(multipart("/api/library/book-1/cover")
                        .file(file)
                        .param("source", "studio")
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isConflict());

        verify(bookCoverService, never()).saveUploadedCover(anyString(), any(), any(), any(), any());
        verify(bookCoverService, never()).saveManualCover(anyString(), any());
        verify(bookCoverService, never()).requestCover(anyString());
    }
}
