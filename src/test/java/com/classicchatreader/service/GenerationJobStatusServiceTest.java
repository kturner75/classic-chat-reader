package com.classicchatreader.service;

import com.classicchatreader.entity.ChapterAnalysisStatus;
import com.classicchatreader.entity.ChapterRecapStatus;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.model.GenerationJobStatusResponse;
import com.classicchatreader.repository.ChapterAnalysisRepository;
import com.classicchatreader.repository.ChapterRecapRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.IllustrationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationJobStatusServiceTest {

    @Mock
    private IllustrationRepository illustrationRepository;

    @Mock
    private CharacterRepository characterRepository;

    @Mock
    private ChapterAnalysisRepository chapterAnalysisRepository;

    @Mock
    private ChapterRecapRepository chapterRecapRepository;

    @Test
    void getGlobalStatus_aggregatesPendingAndScheduledRetries() {
        when(illustrationRepository.countByStatus(IllustrationStatus.PENDING)).thenReturn(5L);
        when(illustrationRepository.countScheduledRetries(eq(IllustrationStatus.PENDING), any(LocalDateTime.class))).thenReturn(2L);
        when(illustrationRepository.countByStatus(IllustrationStatus.GENERATING)).thenReturn(1L);
        when(illustrationRepository.countByStatus(IllustrationStatus.COMPLETED)).thenReturn(7L);
        when(illustrationRepository.countByStatus(IllustrationStatus.FAILED)).thenReturn(3L);

        when(characterRepository.countByStatus(CharacterStatus.PENDING)).thenReturn(4L);
        when(characterRepository.countScheduledRetries(eq(CharacterStatus.PENDING), any(LocalDateTime.class))).thenReturn(1L);
        when(characterRepository.countByStatus(CharacterStatus.GENERATING)).thenReturn(2L);
        when(characterRepository.countByStatus(CharacterStatus.COMPLETED)).thenReturn(6L);
        when(characterRepository.countByStatus(CharacterStatus.FAILED)).thenReturn(1L);

        when(chapterAnalysisRepository.countByStatus(ChapterAnalysisStatus.PENDING)).thenReturn(3L);
        when(chapterAnalysisRepository.countByStatusIsNull()).thenReturn(2L);
        when(chapterAnalysisRepository.countScheduledRetries(eq(ChapterAnalysisStatus.PENDING), any(LocalDateTime.class))).thenReturn(1L);
        when(chapterAnalysisRepository.countByStatus(ChapterAnalysisStatus.GENERATING)).thenReturn(1L);
        when(chapterAnalysisRepository.countByStatus(ChapterAnalysisStatus.COMPLETED)).thenReturn(8L);
        when(chapterAnalysisRepository.countByStatus(ChapterAnalysisStatus.FAILED)).thenReturn(2L);

        when(chapterRecapRepository.countByStatus(ChapterRecapStatus.PENDING)).thenReturn(6L);
        when(chapterRecapRepository.countByStatusIsNull()).thenReturn(1L);
        when(chapterRecapRepository.countScheduledRetries(eq(ChapterRecapStatus.PENDING), any(LocalDateTime.class))).thenReturn(3L);
        when(chapterRecapRepository.countByStatus(ChapterRecapStatus.GENERATING)).thenReturn(2L);
        when(chapterRecapRepository.countByStatus(ChapterRecapStatus.COMPLETED)).thenReturn(9L);
        when(chapterRecapRepository.countByStatus(ChapterRecapStatus.FAILED)).thenReturn(4L);

        GenerationJobStatusService service = new GenerationJobStatusService(
                illustrationRepository,
                characterRepository,
                chapterAnalysisRepository,
                chapterRecapRepository
        );

        GenerationJobStatusResponse response = service.getGlobalStatus();

        assertEquals("global", response.scope());
        assertEquals(3L, response.illustrations().pending());
        assertEquals(2L, response.illustrations().retryScheduled());
        assertEquals(4L, response.analyses().pending());
        assertEquals(1L, response.analyses().retryScheduled());
        assertEquals(4L, response.recaps().pending());
        assertEquals(3L, response.recaps().retryScheduled());
        assertEquals(30L, response.totals().completed());
        assertEquals(10L, response.totals().failed());
    }

    @Test
    void getBookStatus_aggregatesPendingAndScheduledRetriesForSingleBook() {
        String bookId = "book-1";

        when(illustrationRepository.countByBookAndStatus(bookId, IllustrationStatus.PENDING)).thenReturn(6L);
        when(illustrationRepository.countScheduledRetriesForBook(eq(bookId), eq(IllustrationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(2L);
        when(illustrationRepository.countByBookAndStatus(bookId, IllustrationStatus.GENERATING)).thenReturn(1L);
        when(illustrationRepository.countByBookAndStatus(bookId, IllustrationStatus.COMPLETED)).thenReturn(4L);
        when(illustrationRepository.countByBookAndStatus(bookId, IllustrationStatus.FAILED)).thenReturn(1L);

        when(characterRepository.countByBookAndStatus(bookId, CharacterStatus.PENDING)).thenReturn(5L);
        when(characterRepository.countScheduledRetriesForBook(eq(bookId), eq(CharacterStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(characterRepository.countByBookAndStatus(bookId, CharacterStatus.GENERATING)).thenReturn(2L);
        when(characterRepository.countByBookAndStatus(bookId, CharacterStatus.COMPLETED)).thenReturn(3L);
        when(characterRepository.countByBookAndStatus(bookId, CharacterStatus.FAILED)).thenReturn(0L);

        when(chapterAnalysisRepository.countByBookAndStatus(bookId, ChapterAnalysisStatus.PENDING)).thenReturn(2L);
        when(chapterAnalysisRepository.countByChapterBookIdAndStatusIsNull(bookId)).thenReturn(1L);
        when(chapterAnalysisRepository.countScheduledRetriesForBook(eq(bookId), eq(ChapterAnalysisStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(chapterAnalysisRepository.countByBookAndStatus(bookId, ChapterAnalysisStatus.GENERATING)).thenReturn(1L);
        when(chapterAnalysisRepository.countByBookAndStatus(bookId, ChapterAnalysisStatus.COMPLETED)).thenReturn(6L);
        when(chapterAnalysisRepository.countByBookAndStatus(bookId, ChapterAnalysisStatus.FAILED)).thenReturn(1L);

        when(chapterRecapRepository.countByBookAndStatus(bookId, ChapterRecapStatus.PENDING)).thenReturn(3L);
        when(chapterRecapRepository.countByChapterBookIdAndStatusIsNull(bookId)).thenReturn(2L);
        when(chapterRecapRepository.countScheduledRetriesForBook(eq(bookId), eq(ChapterRecapStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(2L);
        when(chapterRecapRepository.countByBookAndStatus(bookId, ChapterRecapStatus.GENERATING)).thenReturn(1L);
        when(chapterRecapRepository.countByBookAndStatus(bookId, ChapterRecapStatus.COMPLETED)).thenReturn(5L);
        when(chapterRecapRepository.countByBookAndStatus(bookId, ChapterRecapStatus.FAILED)).thenReturn(2L);

        GenerationJobStatusService service = new GenerationJobStatusService(
                illustrationRepository,
                characterRepository,
                chapterAnalysisRepository,
                chapterRecapRepository
        );

        GenerationJobStatusResponse response = service.getBookStatus(bookId);

        assertEquals("book", response.scope());
        assertEquals(bookId, response.bookId());
        assertEquals(4L, response.illustrations().pending());
        assertEquals(2L, response.illustrations().retryScheduled());
        assertEquals(4L, response.portraits().pending());
        assertEquals(1L, response.portraits().retryScheduled());
        assertEquals(2L, response.analyses().pending());
        assertEquals(1L, response.analyses().retryScheduled());
        assertEquals(3L, response.recaps().pending());
        assertEquals(2L, response.recaps().retryScheduled());
        assertEquals(18L, response.totals().completed());
        assertEquals(4L, response.totals().failed());
    }
}
