package org.example.reader.service;

import org.example.reader.entity.BookEntity;
import org.example.reader.entity.ChapterAnalysisStatus;
import org.example.reader.entity.ChapterEntity;
import org.example.reader.entity.ChapterRecapStatus;
import org.example.reader.entity.CharacterStatus;
import org.example.reader.entity.IllustrationStatus;
import org.example.reader.repository.BookRepository;
import org.example.reader.repository.CharacterRepository;
import org.example.reader.repository.ChapterAnalysisRepository;
import org.example.reader.repository.ChapterRecapRepository;
import org.example.reader.repository.ChapterRepository;
import org.example.reader.repository.IllustrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreGenerationServiceTest {

    @Mock
    private BookImportService bookImportService;
    @Mock
    private BookStorageService bookStorageService;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private ChapterRepository chapterRepository;
    @Mock
    private ChapterAnalysisRepository chapterAnalysisRepository;
    @Mock
    private ChapterRecapRepository chapterRecapRepository;
    @Mock
    private IllustrationRepository illustrationRepository;
    @Mock
    private CharacterRepository characterRepository;
    @Mock
    private IllustrationService illustrationService;
    @Mock
    private CharacterService characterService;
    @Mock
    private CharacterPrefetchService characterPrefetchService;
    @Mock
    private ChapterRecapService chapterRecapService;

    private PreGenerationService preGenerationService;

    @BeforeEach
    void setUp() {
        preGenerationService = new PreGenerationService(
                bookImportService,
                bookStorageService,
                bookRepository,
                chapterRepository,
                chapterAnalysisRepository,
                chapterRecapRepository,
                illustrationRepository,
                characterRepository,
                illustrationService,
                characterService,
                characterPrefetchService,
                chapterRecapService
        );
        ReflectionTestUtils.setField(preGenerationService, "pollIntervalSeconds", 0);
        ReflectionTestUtils.setField(preGenerationService, "maxWaitMinutes", 1);
    }

    @Test
    void preGenerateForBook_queuesRecapsAndReturnsRecapStats() {
        String bookId = "book-1";
        BookEntity book = new BookEntity("Title", "Author", "gutenberg");
        book.setId(bookId);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        ChapterEntity chapter1 = new ChapterEntity(0, "Chapter 1");
        chapter1.setId("chapter-1");
        ChapterEntity chapter2 = new ChapterEntity(1, "Chapter 2");
        chapter2.setId("chapter-2");
        when(chapterRepository.findByBookIdOrderByChapterIndex(bookId)).thenReturn(List.of(chapter1, chapter2));

        when(illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.COMPLETED)).thenReturn(List.of());
        when(illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.FAILED)).thenReturn(List.of());
        when(illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.PENDING)).thenReturn(List.of());
        when(illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.GENERATING)).thenReturn(List.of());

        when(characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.COMPLETED)).thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.FAILED)).thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.PENDING)).thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.GENERATING)).thenReturn(List.of());

        when(chapterAnalysisRepository.findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.PENDING)).thenReturn(List.of());
        when(chapterAnalysisRepository.findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.GENERATING)).thenReturn(List.of());
        when(chapterAnalysisRepository.findByChapterBookIdAndStatusIsNull(bookId)).thenReturn(List.of());

        when(chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.COMPLETED)).thenReturn(List.of());
        when(chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.FAILED)).thenReturn(List.of());
        when(chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.PENDING)).thenReturn(List.of());
        when(chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.GENERATING)).thenReturn(List.of());
        when(chapterRecapRepository.findByChapterBookIdAndStatusIsNull(bookId)).thenReturn(List.of());

        PreGenerationService.PreGenResult result = preGenerationService.preGenerateForBook(bookId);

        verify(chapterRecapService, times(2)).requestChapterRecap(org.mockito.ArgumentMatchers.anyString());
        verify(characterService).retryFailedPortraitsForBook(bookId);
        assertTrue(result.success());
        assertEquals(2, result.chaptersProcessed());
        assertEquals(0, result.recapsCompleted());
        assertEquals(0, result.recapsFailed());
        assertEquals(0, result.newRecaps());
    }

    @Test
    void preGenerateImagesForBook_skipsRecaps() {
        String bookId = "book-1";
        BookEntity book = new BookEntity("Title", "Author", "gutenberg");
        book.setId(bookId);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        ChapterEntity chapter1 = new ChapterEntity(0, "Chapter 1");
        chapter1.setId("chapter-1");
        ChapterEntity chapter2 = new ChapterEntity(1, "Chapter 2");
        chapter2.setId("chapter-2");
        when(chapterRepository.findByBookIdOrderByChapterIndex(bookId)).thenReturn(List.of(chapter1, chapter2));

        when(illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.COMPLETED)).thenReturn(List.of());
        when(illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.FAILED)).thenReturn(List.of());
        when(illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.PENDING)).thenReturn(List.of());
        when(illustrationRepository.findByChapterBookIdAndStatus(bookId, IllustrationStatus.GENERATING)).thenReturn(List.of());

        when(characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.COMPLETED)).thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.FAILED)).thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.PENDING)).thenReturn(List.of());
        when(characterRepository.findByBookIdAndStatus(bookId, CharacterStatus.GENERATING)).thenReturn(List.of());

        when(chapterAnalysisRepository.findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.PENDING)).thenReturn(List.of());
        when(chapterAnalysisRepository.findByChapterBookIdAndStatus(bookId, ChapterAnalysisStatus.GENERATING)).thenReturn(List.of());
        when(chapterAnalysisRepository.findByChapterBookIdAndStatusIsNull(bookId)).thenReturn(List.of());

        when(chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.COMPLETED)).thenReturn(List.of());
        when(chapterRecapRepository.findByChapterBookIdAndStatus(bookId, ChapterRecapStatus.FAILED)).thenReturn(List.of());

        PreGenerationService.PreGenResult result = preGenerationService.preGenerateImagesForBook(bookId);

        verify(chapterRecapService, never()).requestChapterRecap(org.mockito.ArgumentMatchers.anyString());
        verify(chapterRecapService, never()).forceQueuePendingForBook(bookId);
        verify(characterService).retryFailedPortraitsForBook(bookId);
        assertTrue(result.success());
        assertEquals(2, result.chaptersProcessed());
        assertEquals(0, result.recapsCompleted());
        assertEquals(0, result.recapsFailed());
        assertEquals(0, result.newRecaps());
        assertEquals("No new image assets required", result.message());
    }
}
