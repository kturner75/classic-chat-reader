package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.IllustrationEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.IllustrationRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IllustrationServiceCacheTest {

    @Mock private IllustrationRepository illustrationRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private BookRepository bookRepository;
    @Mock private ParagraphRepository paragraphRepository;
    @Mock private IllustrationPromptService promptService;
    @Mock private IllustrationStyleAnalysisService styleAnalysisService;
    @Mock private ComfyUIService comfyUIService;

    private IllustrationService service;
    private ChapterEntity chapter;
    private IllustrationEntity illustration;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        service = new IllustrationService(
                illustrationRepository,
                chapterRepository,
                bookRepository,
                paragraphRepository,
                promptService,
                styleAnalysisService,
                comfyUIService,
                new AssetKeyService()
        );
        service.setSelf(service);
        ReflectionTestUtils.setField(service, "cacheOnly", true);

        BookEntity book = new BookEntity();
        book.setId("book-1");
        book.setSource("gutenberg");
        book.setSourceId("1342");

        chapter = new ChapterEntity(1, "Chapter II");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        illustration = new IllustrationEntity(chapter);
        illustration.setId("illustration-1");
        illustration.setStatus(IllustrationStatus.FAILED);
        illustration.setErrorMessage("Connection refused");
        illustration.setRetryCount(3);
        cacheKey = "books/gutenberg/1342/illustrations/chapters/1.png";
    }

    @Test
    void requestCreatesCompletedRecordWhenOnlyCachedFileExists() throws Exception {
        when(chapterRepository.findByIdWithBook("chapter-1")).thenReturn(Optional.of(chapter));
        when(illustrationRepository.findByChapterId("chapter-1")).thenReturn(Optional.empty());
        when(comfyUIService.hasImage(cacheKey)).thenReturn(true);

        service.requestIllustration("chapter-1");

        verify(illustrationRepository).save(any(IllustrationEntity.class));
        verify(comfyUIService, never()).submitWorkflow(any(), any(), any());
    }

    @Test
    void startupRecoveryRestoresFailedCachedIllustration() throws Exception {
        when(illustrationRepository.findByChapterBookIdAndStatus("book-1", IllustrationStatus.GENERATING))
                .thenReturn(List.of());
        when(illustrationRepository.findByChapterBookIdAndStatus("book-1", IllustrationStatus.PENDING))
                .thenReturn(List.of());
        when(illustrationRepository.findByChapterBookIdAndStatus("book-1", IllustrationStatus.FAILED))
                .thenReturn(List.of(illustration));
        when(comfyUIService.hasImage(cacheKey)).thenReturn(true);

        int recovered = service.resetAndRequeueStuckForBook("book-1");

        assertEquals(1, recovered);
        assertEquals(IllustrationStatus.COMPLETED, illustration.getStatus());
        assertEquals(cacheKey, illustration.getImageFilename());
        assertEquals(0, illustration.getRetryCount());
        assertNull(illustration.getErrorMessage());
        verify(illustrationRepository).save(illustration);
        verify(comfyUIService, never()).submitWorkflow(any(), any(), any());
    }
}
