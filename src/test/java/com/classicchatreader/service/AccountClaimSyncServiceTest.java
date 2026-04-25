package com.classicchatreader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphAnnotationEntity;
import com.classicchatreader.entity.QuizAttemptEntity;
import com.classicchatreader.entity.QuizTrophyEntity;
import com.classicchatreader.entity.UserReaderStateEntity;
import com.classicchatreader.model.AccountStateSnapshot;
import com.classicchatreader.repository.ParagraphAnnotationRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.QuizTrophyRepository;
import com.classicchatreader.repository.UserReaderClaimRepository;
import com.classicchatreader.repository.UserReaderStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountClaimSyncServiceTest {

    @Mock
    private ParagraphAnnotationRepository paragraphAnnotationRepository;

    @Mock
    private QuizAttemptRepository quizAttemptRepository;

    @Mock
    private QuizTrophyRepository quizTrophyRepository;

    @Mock
    private UserReaderStateRepository userReaderStateRepository;

    @Mock
    private UserReaderClaimRepository userReaderClaimRepository;

    private AccountClaimSyncService accountClaimSyncService;

    @BeforeEach
    void setUp() {
        accountClaimSyncService = new AccountClaimSyncService(
                paragraphAnnotationRepository,
                quizAttemptRepository,
                quizTrophyRepository,
                userReaderStateRepository,
                userReaderClaimRepository,
                new ObjectMapper()
        );
    }

    @Test
    void claimAndSync_claimsAnonymousDataAndMergesIncomingState() {
        String userId = "user-1";
        String readerId = "reader-cookie-1";

        BookEntity book = new BookEntity("Book", "Author", "gutenberg");
        book.setId("book-1");
        ChapterEntity chapter = new ChapterEntity(1, "Chapter 1");
        chapter.setId("chapter-1");
        chapter.setBook(book);

        ParagraphAnnotationEntity sourceAnnotation = new ParagraphAnnotationEntity();
        sourceAnnotation.setReaderId(readerId);
        sourceAnnotation.setBook(book);
        sourceAnnotation.setChapter(chapter);
        sourceAnnotation.setParagraphIndex(0);
        sourceAnnotation.setHighlighted(true);
        sourceAnnotation.setBookmarked(false);
        sourceAnnotation.setNoteText("new note");
        sourceAnnotation.setUpdatedAt(LocalDateTime.of(2026, 2, 18, 10, 0));

        ParagraphAnnotationEntity targetAnnotation = new ParagraphAnnotationEntity();
        targetAnnotation.setUserId(userId);
        targetAnnotation.setBook(book);
        targetAnnotation.setChapter(chapter);
        targetAnnotation.setParagraphIndex(0);
        targetAnnotation.setHighlighted(false);
        targetAnnotation.setBookmarked(false);
        targetAnnotation.setNoteText("old note");
        targetAnnotation.setUpdatedAt(LocalDateTime.of(2026, 2, 18, 9, 0));

        QuizAttemptEntity attempt = new QuizAttemptEntity();
        attempt.setReaderId(readerId);

        QuizTrophyEntity trophy = new QuizTrophyEntity();
        trophy.setReaderId(readerId);
        trophy.setBook(book);
        trophy.setCode("quiz_first_attempt");
        trophy.setTitle("First Checkpoint");
        trophy.setDescription("Complete your first chapter quiz.");

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(false);
        when(paragraphAnnotationRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of(sourceAnnotation));
        when(paragraphAnnotationRepository.findByUserIdAndBook_IdAndChapter_IdAndParagraphIndex(
                userId,
                "book-1",
                "chapter-1",
                0
        )).thenReturn(Optional.of(targetAnnotation));
        when(quizAttemptRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of(attempt));
        when(quizTrophyRepository.findByReaderIdAndUserIdIsNull(readerId)).thenReturn(List.of(trophy));
        when(quizTrophyRepository.findByBookIdAndUserIdAndCode("book-1", userId, "quiz_first_attempt"))
                .thenReturn(Optional.empty());
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.empty());
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountStateSnapshot incoming = new AccountStateSnapshot(
                List.of("book-1"),
                Map.of(),
                new AccountStateSnapshot.ReaderPreferences(1.2, 1.7, 4.0, "warm", "2026-02-18T10:00:00Z"),
                Map.of("book-1", true)
        );

        AccountClaimSyncService.ClaimSyncResult result =
                accountClaimSyncService.claimAndSync(userId, readerId, incoming);

        assertTrue(result.claimApplied());
        assertEquals(List.of("book-1"), result.state().favoriteBookIds());
        assertEquals(true, result.state().recapOptOut().get("book-1"));

        verify(paragraphAnnotationRepository).save(targetAnnotation);
        verify(paragraphAnnotationRepository).delete(sourceAnnotation);

        ArgumentCaptor<QuizAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(QuizAttemptEntity.class);
        verify(quizAttemptRepository).save(attemptCaptor.capture());
        assertEquals(userId, attemptCaptor.getValue().getUserId());

        ArgumentCaptor<UserReaderStateEntity> stateCaptor = ArgumentCaptor.forClass(UserReaderStateEntity.class);
        verify(userReaderStateRepository).save(stateCaptor.capture());
        assertEquals(userId, stateCaptor.getValue().getUserId());
        assertTrue(stateCaptor.getValue().getStateJson().contains("book-1"));
    }

    @Test
    void claimAndSync_whenClaimAlreadyRecorded_skipsAnonymousClaimPass() {
        String userId = "user-1";
        String readerId = "reader-cookie-1";

        UserReaderStateEntity existing = new UserReaderStateEntity();
        existing.setUserId(userId);
        existing.setStateJson("{" +
                "\"favoriteBookIds\":[\"book-existing\"]," +
                "\"bookActivity\":{}," +
                "\"readerPreferences\":null," +
                "\"recapOptOut\":{}" +
                "}");

        when(userReaderClaimRepository.existsByUserIdAndReaderId(userId, readerId)).thenReturn(true);
        when(userReaderStateRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(userReaderStateRepository.save(any(UserReaderStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountClaimSyncService.ClaimSyncResult result =
                accountClaimSyncService.claimAndSync(userId, readerId, AccountStateSnapshot.empty());

        assertEquals(List.of("book-existing"), result.state().favoriteBookIds());
        verify(paragraphAnnotationRepository, never()).findByReaderIdAndUserIdIsNull(eq(readerId));
        verify(quizAttemptRepository, never()).findByReaderIdAndUserIdIsNull(eq(readerId));
        verify(quizTrophyRepository, never()).findByReaderIdAndUserIdIsNull(eq(readerId));
    }
}
