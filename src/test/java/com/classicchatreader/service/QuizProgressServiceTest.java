package com.classicchatreader.service;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.QuizAttemptEntity;
import com.classicchatreader.entity.QuizTrophyEntity;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.QuizTrophyRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuizProgressServiceTest {

    @Mock
    private QuizAttemptRepository quizAttemptRepository;

    @Mock
    private QuizTrophyRepository quizTrophyRepository;

    private QuizProgressService quizProgressService;

    @BeforeEach
    void setUp() {
        quizProgressService = new QuizProgressService(quizAttemptRepository, quizTrophyRepository);
    }

    @Test
    void recordAttemptAndEvaluate_firstPerfectAttempt_unlocksBaselineTrophies() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1);
        QuizAttemptEntity existingPerfect = new QuizAttemptEntity();
        existingPerfect.setPerfect(true);

        when(quizAttemptRepository.save(any(QuizAttemptEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(quizAttemptRepository.countByChapterBookId("book-1")).thenReturn(1L);
        when(quizAttemptRepository.countByChapterBookIdAndPerfectTrue("book-1")).thenReturn(1L);
        when(quizAttemptRepository.findByChapterBookIdOrderByCreatedAtDesc("book-1")).thenReturn(List.of(existingPerfect));
        when(quizTrophyRepository.findByBookIdAndCode("book-1", "quiz_first_attempt")).thenReturn(Optional.empty());
        when(quizTrophyRepository.findByBookIdAndCode("book-1", "quiz_first_perfect")).thenReturn(Optional.empty());
        when(quizTrophyRepository.save(any(QuizTrophyEntity.class))).thenAnswer(invocation -> {
            QuizTrophyEntity entity = invocation.getArgument(0);
            if (entity.getUnlockedAt() == null) {
                entity.setUnlockedAt(LocalDateTime.now());
            }
            return entity;
        });

        QuizProgressService.ProgressUpdate update = quizProgressService.recordAttemptAndEvaluate(
                chapter,
                100,
                3,
                3,
                1
        );

        assertEquals(2, update.newlyUnlocked().size());
        assertTrue(update.newlyUnlocked().stream().anyMatch(trophy -> trophy.code().equals("quiz_first_attempt")));
        assertTrue(update.newlyUnlocked().stream().anyMatch(trophy -> trophy.code().equals("quiz_first_perfect")));
        assertEquals(1L, update.progress().totalAttempts());
        assertEquals(1L, update.progress().perfectAttempts());
        assertEquals(1, update.progress().currentPerfectStreak());

        ArgumentCaptor<QuizAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(QuizAttemptEntity.class);
        verify(quizAttemptRepository).save(attemptCaptor.capture());
        assertEquals(100, attemptCaptor.getValue().getScorePercent());
        assertTrue(attemptCaptor.getValue().isPerfect());
    }

    @Test
    void getBookTrophies_mapsRepositoryEntities() {
        QuizTrophyEntity trophy = new QuizTrophyEntity();
        trophy.setCode("quiz_first_attempt");
        trophy.setTitle("First Checkpoint");
        trophy.setDescription("Complete your first chapter quiz.");
        trophy.setUnlockedAt(LocalDateTime.of(2026, 2, 11, 11, 0));

        when(quizTrophyRepository.findByBookIdOrderByUnlockedAtDesc("book-1")).thenReturn(List.of(trophy));

        var trophies = quizProgressService.getBookTrophies("book-1");

        assertEquals(1, trophies.size());
        assertEquals("quiz_first_attempt", trophies.get(0).code());
        assertFalse(trophies.get(0).title().isBlank());
    }

    @Test
    void recordAttemptAndEvaluate_accountUser_scopesAttemptsAndTrophiesByUserId() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1);
        when(quizAttemptRepository.save(any(QuizAttemptEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(quizAttemptRepository.countByChapterBookIdAndUserId("book-1", "user-1")).thenReturn(1L);
        when(quizAttemptRepository.countByChapterBookIdAndUserIdAndPerfectTrue("book-1", "user-1")).thenReturn(1L);
        when(quizAttemptRepository.findByChapterBookIdAndUserIdOrderByCreatedAtDesc("book-1", "user-1"))
                .thenReturn(List.of());
        when(quizTrophyRepository.findByBookIdAndUserIdAndCode("book-1", "user-1", "quiz_first_attempt"))
                .thenReturn(Optional.empty());
        when(quizTrophyRepository.findByBookIdAndUserIdAndCode("book-1", "user-1", "quiz_first_perfect"))
                .thenReturn(Optional.empty());
        when(quizTrophyRepository.save(any(QuizTrophyEntity.class))).thenAnswer(invocation -> {
            QuizTrophyEntity entity = invocation.getArgument(0);
            if (entity.getUnlockedAt() == null) {
                entity.setUnlockedAt(LocalDateTime.now());
            }
            return entity;
        });

        QuizProgressService.ProgressUpdate update = quizProgressService.recordAttemptAndEvaluate(
                chapter,
                "user-1",
                100,
                3,
                3,
                1
        );

        assertEquals(2, update.newlyUnlocked().size());
        ArgumentCaptor<QuizAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(QuizAttemptEntity.class);
        verify(quizAttemptRepository).save(attemptCaptor.capture());
        assertEquals("user-1", attemptCaptor.getValue().getUserId());
        verify(quizTrophyRepository).findByBookIdAndUserIdAndCode("book-1", "user-1", "quiz_first_attempt");
        verify(quizTrophyRepository).findByBookIdAndUserIdAndCode("book-1", "user-1", "quiz_first_perfect");
    }

    @Test
    void recordAttemptAndEvaluate_anonymousReader_scopesAttemptsAndTrophiesByReaderId() {
        ChapterEntity chapter = createChapter("book-1", "chapter-1", 1);
        when(quizAttemptRepository.save(any(QuizAttemptEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(quizAttemptRepository.countByChapterBookIdAndReaderId("book-1", "reader-cookie-1")).thenReturn(1L);
        when(quizAttemptRepository.countByChapterBookIdAndReaderIdAndPerfectTrue("book-1", "reader-cookie-1"))
                .thenReturn(1L);
        when(quizAttemptRepository.findByChapterBookIdAndReaderIdOrderByCreatedAtDesc("book-1", "reader-cookie-1"))
                .thenReturn(List.of());
        when(quizTrophyRepository.findByBookIdAndReaderIdAndCode("book-1", "reader-cookie-1", "quiz_first_attempt"))
                .thenReturn(Optional.empty());
        when(quizTrophyRepository.findByBookIdAndReaderIdAndCode("book-1", "reader-cookie-1", "quiz_first_perfect"))
                .thenReturn(Optional.empty());
        when(quizTrophyRepository.save(any(QuizTrophyEntity.class))).thenAnswer(invocation -> {
            QuizTrophyEntity entity = invocation.getArgument(0);
            if (entity.getUnlockedAt() == null) {
                entity.setUnlockedAt(LocalDateTime.now());
            }
            return entity;
        });

        QuizProgressService.ProgressUpdate update = quizProgressService.recordAttemptAndEvaluate(
                chapter,
                "reader-cookie-1",
                null,
                100,
                3,
                3,
                1
        );

        assertEquals(2, update.newlyUnlocked().size());
        ArgumentCaptor<QuizAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(QuizAttemptEntity.class);
        verify(quizAttemptRepository).save(attemptCaptor.capture());
        assertEquals("reader-cookie-1", attemptCaptor.getValue().getReaderId());
        assertEquals(null, attemptCaptor.getValue().getUserId());
        verify(quizTrophyRepository).findByBookIdAndReaderIdAndCode("book-1", "reader-cookie-1", "quiz_first_attempt");
        verify(quizTrophyRepository).findByBookIdAndReaderIdAndCode("book-1", "reader-cookie-1", "quiz_first_perfect");
    }

    private ChapterEntity createChapter(String bookId, String chapterId, int chapterIndex) {
        BookEntity book = new BookEntity("Title", "Author", "gutenberg");
        book.setId(bookId);

        ChapterEntity chapter = new ChapterEntity(chapterIndex, "Chapter " + chapterIndex);
        chapter.setId(chapterId);
        chapter.setBook(book);
        return chapter;
    }
}
