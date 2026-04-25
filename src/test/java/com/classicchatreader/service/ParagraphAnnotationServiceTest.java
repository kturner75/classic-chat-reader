package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphAnnotationEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphAnnotationRepository;
import com.classicchatreader.repository.ParagraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParagraphAnnotationServiceTest {

    @Mock
    private ParagraphAnnotationRepository paragraphAnnotationRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private ParagraphRepository paragraphRepository;

    private ParagraphAnnotationService paragraphAnnotationService;

    @BeforeEach
    void setUp() {
        paragraphAnnotationService = new ParagraphAnnotationService(
                paragraphAnnotationRepository,
                bookRepository,
                chapterRepository,
                paragraphRepository
        );
    }

    @Test
    void saveAnnotation_whenChapterNotFound_returnsNotFound() {
        when(chapterRepository.findByIdWithBook("ch-1")).thenReturn(Optional.empty());

        ParagraphAnnotationService.SaveOutcome outcome = paragraphAnnotationService.saveAnnotation(
                "reader-1",
                "book-1",
                "ch-1",
                1,
                true,
                "note",
                false
        );

        assertEquals(ParagraphAnnotationService.SaveStatus.NOT_FOUND, outcome.status());
        verify(paragraphAnnotationRepository, never()).save(any());
    }

    @Test
    void saveAnnotation_whenEmptyPayload_clearsExistingAnnotation() {
        ChapterEntity chapter = chapter("book-1", "ch-1");
        ParagraphAnnotationEntity existing = new ParagraphAnnotationEntity();
        when(chapterRepository.findByIdWithBook("ch-1")).thenReturn(Optional.of(chapter));
        when(paragraphRepository.existsByChapterIdAndParagraphIndex("ch-1", 4)).thenReturn(true);
        when(paragraphAnnotationRepository.findByReaderIdAndBook_IdAndChapter_IdAndParagraphIndex(
                "reader-1", "book-1", "ch-1", 4
        )).thenReturn(Optional.of(existing));

        ParagraphAnnotationService.SaveOutcome outcome = paragraphAnnotationService.saveAnnotation(
                "reader-1",
                "book-1",
                "ch-1",
                4,
                false,
                " ",
                false
        );

        assertEquals(ParagraphAnnotationService.SaveStatus.CLEARED, outcome.status());
        assertNull(outcome.annotation());
        verify(paragraphAnnotationRepository).delete(existing);
    }

    @Test
    void saveAnnotation_savesNormalizedNoteAndFlags() {
        ChapterEntity chapter = chapter("book-1", "ch-2");
        when(chapterRepository.findByIdWithBook("ch-2")).thenReturn(Optional.of(chapter));
        when(paragraphRepository.existsByChapterIdAndParagraphIndex("ch-2", 2)).thenReturn(true);
        when(paragraphAnnotationRepository.findByReaderIdAndBook_IdAndChapter_IdAndParagraphIndex(
                "reader-1", "book-1", "ch-2", 2
        )).thenReturn(Optional.empty());
        when(paragraphAnnotationRepository.save(any(ParagraphAnnotationEntity.class))).thenAnswer(invocation -> {
            ParagraphAnnotationEntity entity = invocation.getArgument(0);
            entity.setUpdatedAt(LocalDateTime.of(2026, 2, 14, 12, 0));
            return entity;
        });

        ParagraphAnnotationService.SaveOutcome outcome = paragraphAnnotationService.saveAnnotation(
                "reader-1",
                "book-1",
                "ch-2",
                2,
                true,
                "  Key quote  ",
                true
        );

        assertEquals(ParagraphAnnotationService.SaveStatus.SAVED, outcome.status());
        assertNotNull(outcome.annotation());
        assertEquals("ch-2", outcome.annotation().chapterId());
        assertEquals(2, outcome.annotation().paragraphIndex());
        assertEquals("Key quote", outcome.annotation().noteText());

        ArgumentCaptor<ParagraphAnnotationEntity> captor = ArgumentCaptor.forClass(ParagraphAnnotationEntity.class);
        verify(paragraphAnnotationRepository).save(captor.capture());
        ParagraphAnnotationEntity saved = captor.getValue();
        assertTrue(saved.isHighlighted());
        assertTrue(saved.isBookmarked());
        assertEquals("Key quote", saved.getNoteText());
    }

    @Test
    void getBookmarkedParagraphs_returnsSanitizedSnippets() {
        ChapterEntity chapter = chapter("book-1", "ch-3");
        chapter.setTitle("Chapter 3");

        ParagraphAnnotationEntity annotation = new ParagraphAnnotationEntity();
        annotation.setChapter(chapter);
        annotation.setParagraphIndex(7);
        annotation.setUpdatedAt(LocalDateTime.of(2026, 2, 14, 13, 30));
        annotation.setBookmarked(true);

        ParagraphEntity paragraph = new ParagraphEntity();
        paragraph.setContent("<em>Hello</em>   world.");

        when(bookRepository.existsById("book-1")).thenReturn(true);
        when(paragraphAnnotationRepository.findByReaderIdAndBook_IdAndBookmarkedTrueOrderByUpdatedAtDesc("reader-1", "book-1"))
                .thenReturn(List.of(annotation));
        when(paragraphRepository.findByChapterIdAndParagraphIndex("ch-3", 7)).thenReturn(Optional.of(paragraph));

        var result = paragraphAnnotationService.getBookmarkedParagraphs("reader-1", "book-1");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertEquals("Chapter 3", result.get().get(0).chapterTitle());
        assertEquals("Hello world.", result.get().get(0).snippet());
    }

    @Test
    void saveAnnotation_withUserId_usesUserScopedLookupAndPersistsUserOwnership() {
        ChapterEntity chapter = chapter("book-1", "ch-9");
        when(chapterRepository.findByIdWithBook("ch-9")).thenReturn(Optional.of(chapter));
        when(paragraphRepository.existsByChapterIdAndParagraphIndex("ch-9", 1)).thenReturn(true);
        when(paragraphAnnotationRepository.findByUserIdAndBook_IdAndChapter_IdAndParagraphIndex(
                "user-1", "book-1", "ch-9", 1
        )).thenReturn(Optional.empty());
        when(paragraphAnnotationRepository.save(any(ParagraphAnnotationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParagraphAnnotationService.SaveOutcome outcome = paragraphAnnotationService.saveAnnotation(
                "user:user-1",
                "user-1",
                "book-1",
                "ch-9",
                1,
                true,
                "Account-owned note",
                false
        );

        assertEquals(ParagraphAnnotationService.SaveStatus.SAVED, outcome.status());
        ArgumentCaptor<ParagraphAnnotationEntity> captor = ArgumentCaptor.forClass(ParagraphAnnotationEntity.class);
        verify(paragraphAnnotationRepository).save(captor.capture());
        assertEquals("user-1", captor.getValue().getUserId());
        assertEquals("user:user-1", captor.getValue().getReaderId());
    }

    private ChapterEntity chapter(String bookId, String chapterId) {
        BookEntity book = new BookEntity("Title", "Author", "gutenberg");
        book.setId(bookId);

        ChapterEntity chapter = new ChapterEntity(1, "Chapter 1");
        chapter.setId(chapterId);
        chapter.setBook(book);
        return chapter;
    }
}
