package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentChapterEntity;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.AssignmentProgressEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.EnrollmentEntity;
import com.classicchatreader.entity.QuizAttemptEntity;
import com.classicchatreader.entity.UserEntity;
import com.classicchatreader.entity.UserReaderStateEntity;
import com.classicchatreader.repository.AssignmentProgressRepository;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterChatConversationRepository;
import com.classicchatreader.repository.EnrollmentRepository;
import com.classicchatreader.repository.QuizAttemptRepository;
import com.classicchatreader.repository.UserReaderStateRepository;
import com.classicchatreader.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherStudentOverviewServiceTest {

    @Mock private ClassroomAuthorizationService authorizationService;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private AssignmentProgressRepository assignmentProgressRepository;
    @Mock private BookRepository bookRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private QuizAttemptRepository quizAttemptRepository;
    @Mock private UserReaderStateRepository userReaderStateRepository;
    @Mock private CharacterChatConversationRepository characterChatConversationRepository;
    @Mock private ClassroomUsageService classroomUsageService;
    @Mock private ClassroomProperties classroomProperties;

    private TeacherStudentOverviewService service;

    @BeforeEach
    void setUp() {
        service = new TeacherStudentOverviewService(
                authorizationService,
                enrollmentRepository,
                userRepository,
                assignmentRepository,
                assignmentProgressRepository,
                bookRepository,
                chapterRepository,
                quizAttemptRepository,
                userReaderStateRepository,
                characterChatConversationRepository,
                classroomUsageService,
                classroomProperties,
                new ObjectMapper()
        );
    }

    @Test
    void forbidsNonTeacher() {
        when(userRepository.existsById("student-2")).thenReturn(true);
        when(authorizationService.canManageTerm("student-2", "term-1")).thenReturn(false);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.getOverview("student-2", "term-1", "student-1")
        );
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void returns404WhenStudentNotOnRoster() {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(enrollmentRepository.findByTermIdAndUserIdAndDeletedAtIsNull("term-1", "student-1"))
                .thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.getOverview("teacher-1", "term-1", "student-1")
        );
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void aggregatesCurrentCompletedOpenedProgressQuizAndTime() throws Exception {
        when(userRepository.existsById("teacher-1")).thenReturn(true);
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);

        EnrollmentEntity enrollment = new EnrollmentEntity();
        enrollment.setId("enr-1");
        enrollment.setTermId("term-1");
        enrollment.setUserId("student-1");
        enrollment.setStatus("ACTIVE");
        enrollment.setJoinedDate(LocalDate.of(2026, 8, 1));
        enrollment.setDisplayNameOverride("Alex");
        when(enrollmentRepository.findByTermIdAndUserIdAndDeletedAtIsNull("term-1", "student-1"))
                .thenReturn(Optional.of(enrollment));

        UserEntity student = new UserEntity();
        student.setId("student-1");
        student.setEmail("alex@example.test");
        when(userRepository.findById("student-1")).thenReturn(Optional.of(student));

        AssignmentEntity openAssign = publishedAssignment("a-open", "Not opened yet", "book-2", "ch-9", 0, false);
        AssignmentEntity inProgress = publishedAssignment("a-progress", "In progress", "book-1", "ch-2", 1, true);
        AssignmentEntity complete = publishedAssignment("a-done", "Done", "book-1", "ch-1", 0, true);
        when(assignmentRepository.findByTermIdAndStatusAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc(
                "term-1", "PUBLISHED"))
                .thenReturn(List.of(openAssign, inProgress, complete));

        AssignmentProgressEntity opened = new AssignmentProgressEntity();
        opened.setAssignmentId("a-progress");
        opened.setTermId("term-1");
        opened.setUserId("student-1");
        opened.setFirstOpenedAt(LocalDateTime.of(2026, 8, 10, 12, 0));
        AssignmentProgressEntity openedDone = new AssignmentProgressEntity();
        openedDone.setAssignmentId("a-done");
        openedDone.setTermId("term-1");
        openedDone.setUserId("student-1");
        openedDone.setFirstOpenedAt(LocalDateTime.of(2026, 8, 9, 12, 0));
        when(assignmentProgressRepository.findByTermIdAndUserId("term-1", "student-1"))
                .thenReturn(List.of(opened, openedDone));

        BookEntity book = new BookEntity();
        book.setId("book-1");
        book.setTitle("Pride and Prejudice");
        BookEntity book2 = new BookEntity();
        book2.setId("book-2");
        book2.setTitle("Unopened Classic");
        when(bookRepository.findAllById(any())).thenReturn(List.of(book, book2));

        ChapterEntity ch1 = chapter("ch-1", book, 0, "Chapter I");
        ChapterEntity ch2 = chapter("ch-2", book, 1, "Chapter II");
        ChapterEntity ch9 = chapter("ch-9", book2, 0, "Chapter I");
        when(chapterRepository.findById("ch-1")).thenReturn(Optional.of(ch1));
        when(chapterRepository.findById("ch-2")).thenReturn(Optional.of(ch2));
        when(chapterRepository.findById("ch-9")).thenReturn(Optional.of(ch9));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-2")).thenReturn(List.of(ch9));

        UserReaderStateEntity readerState = new UserReaderStateEntity();
        readerState.setUserId("student-1");
        readerState.setStateJson("""
                {
                  "favoriteBookIds": [],
                  "bookActivity": {
                    "book-1": {
                      "chapterCount": 2,
                      "lastChapterIndex": 1,
                      "lastPage": 9,
                      "totalPages": 10,
                      "maxProgressRatio": 0.75,
                      "progressRatio": 0.75,
                      "completed": false,
                      "lastReadAt": "2026-08-11T15:00:00Z"
                    }
                  }
                }
                """);
        when(userReaderStateRepository.findById("student-1")).thenReturn(Optional.of(readerState));

        when(quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(eq("a-done"), eq("student-1"), any()))
                .thenReturn(2L);
        when(quizAttemptRepository.countByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(eq("a-progress"), eq("student-1"), any()))
                .thenReturn(1L);
        when(quizAttemptRepository.findMaxCorrectAnswersByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("a-done"), eq("student-1"), any())).thenReturn(8);
        when(quizAttemptRepository.findMaxCorrectAnswersByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("a-progress"), eq("student-1"), any())).thenReturn(4);
        when(quizAttemptRepository.findMaxScorePercentByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("a-done"), eq("student-1"), any())).thenReturn(80);
        when(quizAttemptRepository.findMaxScorePercentByAssignmentIdAndUserIdAndCreatedAtOnOrAfter(
                eq("a-progress"), eq("student-1"), any())).thenReturn(40);
        when(quizAttemptRepository.findByAssignmentIdAndUserIdOrderByCreatedAtDesc("a-done", "student-1"))
                .thenReturn(List.of(attempt(8, 10, 80)));
        when(quizAttemptRepository.findByAssignmentIdAndUserIdOrderByCreatedAtDesc("a-progress", "student-1"))
                .thenReturn(List.of(attempt(4, 10, 40)));

        when(classroomUsageService.sumApproximateReaderMsByBook("term-1", "student-1"))
                .thenReturn(Map.of("book-1", 125000L));
        when(classroomUsageService.sumApproximateReaderMs("term-1", "student-1")).thenReturn(125000L);

        TeacherStudentOverviewService.StudentOverviewResponse overview =
                service.getOverview("teacher-1", "term-1", "student-1");

        assertEquals("alex@example.test", overview.student().email());
        assertEquals(1, overview.completedAssignments().size());
        assertEquals("a-done", overview.completedAssignments().get(0).assignmentId());
        assertTrue(overview.completedAssignments().get(0).opened());

        assertEquals(2, overview.currentAssignments().size());
        TeacherStudentOverviewService.AssignmentOverview notOpened = overview.currentAssignments().stream()
                .filter(a -> "a-open".equals(a.assignmentId()))
                .findFirst()
                .orElseThrow();
        assertFalse(notOpened.opened());
        assertEquals("Not started", notOpened.statusLabel());

        TeacherStudentOverviewService.AssignmentOverview started = overview.currentAssignments().stream()
                .filter(a -> "a-progress".equals(a.assignmentId()))
                .findFirst()
                .orElseThrow();
        assertTrue(started.opened());
        assertEquals("In progress", started.statusLabel());
        assertEquals(1, started.quizAttemptsUsed());

        assertEquals(2, overview.progressByBook().size());
        TeacherStudentOverviewService.BookProgress pride = overview.progressByBook().stream()
                .filter(p -> "book-1".equals(p.bookId()))
                .findFirst()
                .orElseThrow();
        assertEquals("2/2", pride.chapterLabel());
        assertEquals(75, pride.percentComplete());

        assertEquals(2, overview.quizzesForBook().size());
        TeacherStudentOverviewService.QuizOverview doneQuiz = overview.quizzesForBook().stream()
                .filter(q -> "a-done".equals(q.assignmentId()))
                .findFirst()
                .orElseThrow();
        assertTrue(doneQuiz.complete());
        assertEquals(80, doneQuiz.bestScorePercent());
        assertEquals(1, doneQuiz.retryAttemptsUsed());

        assertEquals(125000L, overview.timeInReader().approximateTotalMs());
        assertTrue(overview.timeInReader().label().toLowerCase().contains("approximate"));
    }

    private static AssignmentEntity publishedAssignment(
            String id, String title, String bookId, String chapterId, int chapterIndex, boolean quizRequired) {
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setId(id);
        assignment.setTermId("term-1");
        assignment.setTitle(title);
        assignment.setBookId(bookId);
        AssignmentChapterEntity row = new AssignmentChapterEntity();
        row.setChapterId(chapterId);
        row.setChapterIndex(chapterIndex);
        row.setSortOrder(chapterIndex);
        assignment.replaceChapters(List.of(row));
        assignment.setQuizRequired(quizRequired);
        if (quizRequired) {
            assignment.setQuizPassMinCorrect(7);
            assignment.setQuizMaxRetries(2);
        }
        assignment.setStatus("PUBLISHED");
        assignment.setCreatedAt(LocalDateTime.of(2026, 8, 1, 0, 0));
        return assignment;
    }

    private static ChapterEntity chapter(String id, BookEntity book, int index, String title) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(id);
        chapter.setBook(book);
        chapter.setChapterIndex(index);
        chapter.setTitle(title);
        return chapter;
    }

    private static QuizAttemptEntity attempt(int correct, int total, int score) {
        QuizAttemptEntity attempt = new QuizAttemptEntity();
        attempt.setCorrectAnswers(correct);
        attempt.setTotalQuestions(total);
        attempt.setScorePercent(score);
        attempt.setCreatedAt(LocalDateTime.of(2026, 8, 10, 14, 0));
        return attempt;
    }
}
