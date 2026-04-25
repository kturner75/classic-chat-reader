package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.repository.BookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationQueueRecoveryServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private IllustrationService illustrationService;

    @Mock
    private CharacterService characterService;

    @Mock
    private ChapterRecapService chapterRecapService;

    @Test
    void recoverPendingGenerationWork_whenDisabled_skipsRecovery() {
        GenerationQueueRecoveryService service = new GenerationQueueRecoveryService(
                bookRepository,
                illustrationService,
                characterService,
                chapterRecapService,
                false
        );

        GenerationQueueRecoveryService.RecoverySummary summary = service.recoverPendingGenerationWork();

        assertEquals(0, summary.booksScanned());
        assertEquals(0, summary.illustrationsRequeued());
        assertEquals(0, summary.portraitsRequeued());
        assertEquals(0, summary.analysesRequeued());
        assertEquals(0, summary.recapsRequeued());
        verifyNoInteractions(bookRepository);
        verifyNoInteractions(illustrationService);
        verifyNoInteractions(characterService);
        verifyNoInteractions(chapterRecapService);
    }

    @Test
    void recoverPendingGenerationWork_whenEnabled_requeuesAcrossAllBooks() {
        BookEntity bookOne = new BookEntity();
        bookOne.setId("book-1");
        BookEntity bookTwo = new BookEntity();
        bookTwo.setId("book-2");

        when(bookRepository.findAll()).thenReturn(List.of(bookOne, bookTwo));
        when(illustrationService.resetAndRequeueStuckForBook("book-1")).thenReturn(2);
        when(illustrationService.resetAndRequeueStuckForBook("book-2")).thenReturn(1);
        when(characterService.resetAndRequeueStuckPortraitsForBook("book-1")).thenReturn(3);
        when(characterService.resetAndRequeueStuckPortraitsForBook("book-2")).thenReturn(1);
        when(characterService.resetAndRequeueStuckAnalysesForBook("book-1")).thenReturn(4);
        when(characterService.resetAndRequeueStuckAnalysesForBook("book-2")).thenReturn(2);
        when(chapterRecapService.resetAndRequeueStuckForBook("book-1")).thenReturn(5);
        when(chapterRecapService.resetAndRequeueStuckForBook("book-2")).thenReturn(1);

        GenerationQueueRecoveryService service = new GenerationQueueRecoveryService(
                bookRepository,
                illustrationService,
                characterService,
                chapterRecapService,
                true
        );

        GenerationQueueRecoveryService.RecoverySummary summary = service.recoverPendingGenerationWork();

        assertEquals(2, summary.booksScanned());
        assertEquals(3, summary.illustrationsRequeued());
        assertEquals(4, summary.portraitsRequeued());
        assertEquals(6, summary.analysesRequeued());
        assertEquals(6, summary.recapsRequeued());
        verify(bookRepository).findAll();
        verify(illustrationService).resetAndRequeueStuckForBook("book-1");
        verify(illustrationService).resetAndRequeueStuckForBook("book-2");
        verify(characterService).resetAndRequeueStuckPortraitsForBook("book-1");
        verify(characterService).resetAndRequeueStuckPortraitsForBook("book-2");
        verify(characterService).resetAndRequeueStuckAnalysesForBook("book-1");
        verify(characterService).resetAndRequeueStuckAnalysesForBook("book-2");
        verify(chapterRecapService).resetAndRequeueStuckForBook("book-1");
        verify(chapterRecapService).resetAndRequeueStuckForBook("book-2");
    }

    @Test
    void recoverPendingGenerationWork_ignoresBookWithoutId() {
        BookEntity missingId = new BookEntity();
        when(bookRepository.findAll()).thenReturn(List.of(missingId));

        GenerationQueueRecoveryService service = new GenerationQueueRecoveryService(
                bookRepository,
                illustrationService,
                characterService,
                chapterRecapService,
                true
        );

        GenerationQueueRecoveryService.RecoverySummary summary = service.recoverPendingGenerationWork();

        assertEquals(1, summary.booksScanned());
        assertEquals(0, summary.illustrationsRequeued());
        assertEquals(0, summary.portraitsRequeued());
        assertEquals(0, summary.analysesRequeued());
        assertEquals(0, summary.recapsRequeued());
        verify(bookRepository).findAll();
        verifyNoInteractions(illustrationService);
        verifyNoInteractions(characterService);
        verifyNoInteractions(chapterRecapService);
    }
}
