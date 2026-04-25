package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomDemoProperties;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassroomContextServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private QuizAttemptRepository quizAttemptRepository;

    private ClassroomDemoProperties properties;
    private ClassroomContextService classroomContextService;

    @BeforeEach
    void setUp() {
        properties = new ClassroomDemoProperties();
        classroomContextService = new ClassroomContextService(
                properties,
                bookRepository,
                chapterRepository,
                quizAttemptRepository
        );
    }

    @Test
    void getContextReturnsNotEnrolledWhenDisabled() {
        ClassroomContextResponse context = classroomContextService.getContext();

        assertFalse(context.enrolled());
        assertTrue(context.assignments().isEmpty());
        verify(bookRepository, never()).findById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getContextResolvesAssignmentAndQuizCompletion() {
        properties.setEnabled(true);
        properties.setClassId("class-1");
        properties.setClassName("English 8");
        ClassroomDemoProperties.Features features = new ClassroomDemoProperties.Features();
        features.setRecapEnabled(false);
        properties.setFeatures(features);

        ClassroomDemoProperties.Assignment assignment = new ClassroomDemoProperties.Assignment();
        assignment.setAssignmentId("assign-1");
        assignment.setTitle("Read Chapter 1");
        assignment.setBookId("book-1");
        assignment.setChapterIndex(0);
        assignment.setQuizRequired(true);
        assignment.setDueAt("2026-02-20T23:59:00Z");
        properties.setAssignments(List.of(assignment));

        BookEntity book = new BookEntity();
        book.setId("book-1");
        book.setTitle("Treasure Island");
        book.setAuthor("Robert Louis Stevenson");

        ChapterEntity chapter = new ChapterEntity();
        chapter.setId("chapter-1");
        chapter.setBook(book);
        chapter.setChapterIndex(0);
        chapter.setTitle("Chapter One");

        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        when(chapterRepository.findByBookIdAndChapterIndex("book-1", 0)).thenReturn(Optional.of(chapter));
        when(quizAttemptRepository.existsByChapterId("chapter-1")).thenReturn(true);

        ClassroomContextResponse context = classroomContextService.getContext();

        assertTrue(context.enrolled());
        assertEquals("class-1", context.classId());
        assertEquals("English 8", context.className());
        assertFalse(context.features().recapEnabled());
        assertEquals(1, context.assignments().size());
        assertEquals("book-1", context.assignments().get(0).bookId());
        assertEquals("Treasure Island", context.assignments().get(0).bookTitle());
        assertEquals(ClassroomContextResponse.QuizRequirementStatus.COMPLETE, context.assignments().get(0).quizStatus());
    }

    @Test
    void getContextMarksQuizStatusUnknownWhenRequiredChapterMissing() {
        properties.setEnabled(true);

        ClassroomDemoProperties.Assignment assignment = new ClassroomDemoProperties.Assignment();
        assignment.setBookId("book-1");
        assignment.setQuizRequired(true);
        properties.setAssignments(List.of(assignment));

        BookEntity book = new BookEntity();
        book.setId("book-1");
        book.setTitle("Invisible Book");
        book.setAuthor("Unknown");
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));

        ClassroomContextResponse context = classroomContextService.getContext();

        assertEquals(1, context.assignments().size());
        assertEquals(ClassroomContextResponse.QuizRequirementStatus.UNKNOWN, context.assignments().get(0).quizStatus());
        verify(quizAttemptRepository, never()).existsByChapterId(org.mockito.ArgumentMatchers.anyString());
    }
}
