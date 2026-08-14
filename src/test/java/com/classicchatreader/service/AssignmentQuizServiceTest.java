package com.classicchatreader.service;

import com.classicchatreader.config.ClassroomProperties;
import com.classicchatreader.entity.AssignmentChapterEntity;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.AssignmentQuizEntity;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.ParagraphEntity;
import com.classicchatreader.entity.UserReaderStateEntity;
import com.classicchatreader.model.ChapterQuizGradeResponse;
import com.classicchatreader.model.ChapterQuizPayload;
import com.classicchatreader.model.ChapterQuizViewPayload;
import com.classicchatreader.model.QuizProgress;
import com.classicchatreader.repository.AssignmentQuizRepository;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.repository.UserReaderStateRepository;
import com.classicchatreader.service.llm.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentQuizServiceTest {

    @Mock private ClassroomAuthorizationService authorizationService;
    @Mock private AssignmentRepository assignmentRepository;
    @Mock private AssignmentQuizRepository assignmentQuizRepository;
    @Mock private BookRepository bookRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private ParagraphRepository paragraphRepository;
    @Mock private ChapterQuizService chapterQuizService;
    @Mock private ClassroomEffectiveQuizService classroomEffectiveQuizService;
    @Mock private ClassroomQuizPolicyService classroomQuizPolicyService;
    @Mock private QuizProgressService quizProgressService;
    @Mock private ClassroomProperties classroomProperties;
    @Mock private UserReaderStateRepository userReaderStateRepository;
    @Mock private LlmProvider reasoningProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssignmentQuizService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentQuizService(
                authorizationService,
                assignmentRepository,
                assignmentQuizRepository,
                bookRepository,
                chapterRepository,
                paragraphRepository,
                chapterQuizService,
                classroomEffectiveQuizService,
                classroomQuizPolicyService,
                quizProgressService,
                classroomProperties,
                userReaderStateRepository,
                reasoningProvider,
                objectMapper,
                passthroughTransactionManager()
        );
    }

    private static PlatformTransactionManager passthroughTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    private void stubReadingComplete() {
        UserReaderStateEntity state = new UserReaderStateEntity();
        state.setStateJson("{\"bookActivity\":{\"book-1\":{\"completed\":true}}}");
        when(userReaderStateRepository.findById("user-1")).thenReturn(Optional.of(state));
    }

    @Test
    void getStudentQuiz_usesCustomPayload() throws Exception {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
        stubReadingComplete();
        AssignmentQuizEntity quiz = new AssignmentQuizEntity();
        quiz.setAssignmentId("asg-1");
        quiz.setPayloadJson(objectMapper.writeValueAsString(samplePayload("Custom Q")));
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.isActiveStudentOnTerm("user-1", "term-1")).thenReturn(true);
        when(assignmentQuizRepository.findByAssignmentId("asg-1")).thenReturn(Optional.of(quiz));
        when(chapterQuizService.toStudentView(any())).thenReturn(new ChapterQuizViewPayload(List.of(
                new ChapterQuizViewPayload.Question("q1", "Custom Q", List.of("A", "B"))
        )));

        Optional<AssignmentQuizService.AssignmentQuizViewResponse> view =
                service.getStudentQuiz("user-1", "asg-1");

        assertTrue(view.isPresent());
        assertEquals("CUSTOM", view.get().quizSource());
        assertEquals("Custom Q", view.get().payload().questions().get(0).question());
    }

    @Test
    void getStudentQuiz_fallsBackToChapterQuiz() {
        AssignmentEntity assignment = publishedAssignment("CHAPTER");
        stubReadingComplete();
        assignment.replaceChapters(List.of(chapterRow("chapter-1", 0)));
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.isActiveStudentOnTerm("user-1", "term-1")).thenReturn(true);
        when(classroomEffectiveQuizService.loadEffectivePayloadForTerm("term-1", "chapter-1"))
                .thenReturn(Optional.of(samplePayload("Chapter Q")));
        when(chapterQuizService.toStudentView(any())).thenReturn(new ChapterQuizViewPayload(List.of(
                new ChapterQuizViewPayload.Question("q1", "Chapter Q", List.of("A", "B"))
        )));

        Optional<AssignmentQuizService.AssignmentQuizViewResponse> view =
                service.getStudentQuiz("user-1", "asg-1");

        assertTrue(view.isPresent());
        assertEquals("CHAPTER", view.get().quizSource());
        assertEquals("Chapter Q", view.get().payload().questions().get(0).question());
    }

    @Test
    void getStudentQuiz_rejectsBeforeAvailableFromDate() {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
        assignment.setAvailableFromDate(LocalDate.of(2099, 1, 15));
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.isActiveStudentOnTerm("user-1", "term-1")).thenReturn(true);
        when(classroomProperties.today()).thenReturn(LocalDate.of(2099, 1, 14));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.getStudentQuiz("user-1", "asg-1"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void gradeStudentQuiz_recordsAssignmentScopedAttempt() {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
        stubReadingComplete();
        AssignmentQuizEntity quiz = new AssignmentQuizEntity();
        quiz.setAssignmentId("asg-1");
        quiz.setPayloadJson("""
                {"questions":[{"id":"q1","question":"Who?","options":["A","B"],"correctOptionIndex":0}]}
                """);
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.isActiveStudentOnTerm("user-1", "term-1")).thenReturn(true);
        when(assignmentQuizRepository.findByAssignmentId("asg-1")).thenReturn(Optional.of(quiz));
        when(quizProgressService.recordAttemptAndEvaluate(
                isNull(), eq("book-1"), eq("asg-1"), isNull(), eq("user-1"), eq(100), eq(1), eq(1), eq(0)))
                .thenReturn(new QuizProgressService.ProgressUpdate(List.of(), new QuizProgress(1, 1, 1)));
        when(chapterQuizService.contentVersion(any())).thenReturn("v1");

        Optional<ChapterQuizGradeResponse> result = service.gradeStudentQuiz(
                "user-1", "asg-1", List.of(0), List.of("q1"), "v1", null);

        assertTrue(result.isPresent());
        assertEquals(100, result.get().scorePercent());
        verify(classroomQuizPolicyService).assertCanAttemptAssignment("asg-1", "user-1");
        verify(quizProgressService).recordAttemptAndEvaluate(
                isNull(), eq("book-1"), eq("asg-1"), isNull(), eq("user-1"), eq(100), eq(1), eq(1), eq(0));
    }

    @Test
    void saveCustomQuiz_setsQuizSourceCustom() {
        AssignmentEntity assignment = publishedAssignment(null);
        assignment.setStatus("DRAFT");
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(assignmentQuizRepository.findByAssignmentId("asg-1")).thenReturn(Optional.empty());
        when(assignmentQuizRepository.save(any(AssignmentQuizEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chapterQuizService.serializePayload(any())).thenReturn("{\"questions\":[]}");
        when(chapterQuizService.contentVersion(any())).thenReturn("v1");

        AssignmentQuizService.AssignmentEffectiveQuizResponse saved = service.saveCustomQuiz(
                "teacher-1",
                "asg-1",
                new AssignmentQuizService.SaveAssignmentQuizRequest(List.of(
                        new ChapterQuizPayload.Question("q1", "Who?", List.of("A", "B", "C", "D"), 0, null, null)
                )));

        assertEquals("CUSTOM", saved.quizSource());
        ArgumentCaptor<AssignmentEntity> captor = ArgumentCaptor.forClass(AssignmentEntity.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals(AssignmentEntity.QUIZ_SOURCE_CUSTOM, captor.getValue().getQuizSource());
    }

    @Test
    void saveCustomQuiz_doesNotResetAttemptWindowWhenContentUnchanged() {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
        assignment.setQuizPassMinCorrect(1);
        assignment.setQuizMaxRetries(2);
        AssignmentQuizEntity existing = new AssignmentQuizEntity();
        existing.setAssignmentId("asg-1");
        existing.setPayloadJson("{\"questions\":[{\"id\":\"q1\"}]}");
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(assignmentQuizRepository.findByAssignmentId("asg-1")).thenReturn(Optional.of(existing));
        when(assignmentQuizRepository.save(any(AssignmentQuizEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chapterQuizService.serializePayload(any())).thenReturn("{\"questions\":[{\"id\":\"q1\"}]}");
        when(chapterQuizService.contentVersion(any())).thenReturn("same-version");

        service.saveCustomQuiz(
                "teacher-1",
                "asg-1",
                new AssignmentQuizService.SaveAssignmentQuizRequest(List.of(
                        new ChapterQuizPayload.Question("q1", "Who?", List.of("A", "B", "C", "D"), 0, null, null)
                )));

        ArgumentCaptor<AssignmentEntity> captor = ArgumentCaptor.forClass(AssignmentEntity.class);
        verify(assignmentRepository).save(captor.capture());
        assertEquals(null, captor.getValue().getQuizRulesActivatedAt());
    }

    @Test
    void saveCustomQuiz_resetsAttemptWindowWhenContentChanges() {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
        assignment.setQuizPassMinCorrect(1);
        assignment.setQuizMaxRetries(2);
        AssignmentQuizEntity existing = new AssignmentQuizEntity();
        existing.setAssignmentId("asg-1");
        existing.setPayloadJson("{\"questions\":[{\"id\":\"q1\"}]}");
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(assignmentQuizRepository.findByAssignmentId("asg-1")).thenReturn(Optional.of(existing));
        when(assignmentQuizRepository.save(any(AssignmentQuizEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.save(any(AssignmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chapterQuizService.serializePayload(any())).thenReturn("{\"questions\":[{\"id\":\"q2\"}]}");
        when(chapterQuizService.contentVersion(any())).thenReturn("new-version", "old-version", "new-version");

        service.saveCustomQuiz(
                "teacher-1",
                "asg-1",
                new AssignmentQuizService.SaveAssignmentQuizRequest(List.of(
                        new ChapterQuizPayload.Question("q2", "Where?", List.of("A", "B", "C", "D"), 1, null, null)
                )));

        ArgumentCaptor<AssignmentEntity> captor = ArgumentCaptor.forClass(AssignmentEntity.class);
        verify(assignmentRepository).save(captor.capture());
        assertTrue(captor.getValue().getQuizRulesActivatedAt() != null);
    }

    @Test
    void isChapterDefaultAvailable_requiresSingleChapterWithQuestions() {
        AssignmentEntity multi = publishedAssignment("CHAPTER");
        AssignmentChapterEntity first = chapterRow("ch-1", 0);
        AssignmentChapterEntity second = chapterRow("ch-2", 1);
        multi.replaceChapters(List.of(first, second));
        assertFalse(service.isChapterDefaultAvailable(multi));

        AssignmentEntity single = publishedAssignment("CHAPTER");
        single.replaceChapters(List.of(chapterRow("chapter-1", 0)));
        when(classroomEffectiveQuizService.resolveEffectiveQuestionCount("term-1", "chapter-1"))
                .thenReturn(Optional.of(4));
        assertTrue(service.isChapterDefaultAvailable(single));
    }

    @Test
    void suggestDistractors_skipsExcludedAndCorrectChoices() {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of());
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.generate(any(), any()))
                .thenReturn("{\"distractors\":[\"Paris\",\"London\",\"Rome\",\"Madrid\"]}");

        List<String> result = service.suggestDistractors(
                "teacher-1",
                "asg-1",
                new TeacherQuizAuthoringService.SuggestDistractorsRequest(
                        "Capital?", "Paris", 2, null, null, List.of("London")));

        assertEquals(List.of("Rome", "Madrid"), result);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(reasoningProvider).generate(prompt.capture(), any());
        assertTrue(prompt.getValue().contains("Do not reuse these existing choices: London"));
    }

    @Test
    void suggestQuestions_filtersProposedChaptersToRequestedBook() {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
        BookEntity assignedBook = new BookEntity("Assigned", "Author", "manual");
        assignedBook.setId("book-1");
        ChapterEntity inBook = new ChapterEntity(0, "In-book chapter");
        inBook.setId("ch-in-book");
        inBook.setBook(assignedBook);
        BookEntity otherBook = new BookEntity("Other", "Author", "manual");
        otherBook.setId("book-2");
        ChapterEntity other = new ChapterEntity(0, "Other-book chapter");
        other.setId("ch-other-book");
        other.setBook(otherBook);

        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(assignedBook));
        when(chapterRepository.findByIdWithBook("ch-in-book")).thenReturn(Optional.of(inBook));
        when(chapterRepository.findByIdWithBook("ch-other-book")).thenReturn(Optional.of(other));
        when(paragraphRepository.findByChapterIdOrderByParagraphIndex("ch-in-book"))
                .thenReturn(List.of(new ParagraphEntity(0, "Assigned-book secret text.")));
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.generate(any(), any())).thenReturn("""
                {"questions":[{"id":"q1","question":"Who?","options":["A","B","C","D"],"correctOptionIndex":0}]}
                """);

        service.suggestQuestions(
                "teacher-1",
                "asg-1",
                new TeacherQuizAuthoringService.SuggestQuestionsRequest(
                        1, 4, "book-1", List.of("ch-in-book", "ch-other-book")));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(reasoningProvider).generate(prompt.capture(), any());
        assertTrue(prompt.getValue().contains("Assigned-book secret text."));
        assertFalse(prompt.getValue().contains("Other-book chapter"));
        verify(paragraphRepository, org.mockito.Mockito.never())
                .findByChapterIdOrderByParagraphIndex("ch-other-book");
    }

    @Test
    void suggestQuestions_omitsExceptionTextFromBadGateway() {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
        BookEntity book = new BookEntity("Assigned", "Author", "manual");
        book.setId("book-1");
        when(assignmentRepository.findByIdAndDeletedAtIsNull("asg-1")).thenReturn(Optional.of(assignment));
        when(authorizationService.canManageTerm("teacher-1", "term-1")).thenReturn(true);
        when(bookRepository.findById("book-1")).thenReturn(Optional.of(book));
        when(chapterRepository.findByBookIdOrderByChapterIndex("book-1")).thenReturn(List.of());
        when(reasoningProvider.isAvailable()).thenReturn(true);
        when(reasoningProvider.generate(any(), any()))
                .thenThrow(new RuntimeException("upstream leaked api key sk-secret"));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.suggestQuestions(
                        "teacher-1",
                        "asg-1",
                        new TeacherQuizAuthoringService.SuggestQuestionsRequest(1, 4)));

        assertEquals(502, ex.getStatusCode().value());
        assertEquals("Failed to suggest quiz questions.", ex.getReason());
        assertFalse(ex.getReason().contains("sk-secret"));
        assertFalse(ex.getReason().contains("upstream leaked"));
    }

    private static AssignmentEntity publishedAssignment(String quizSource) {
        AssignmentEntity assignment = new AssignmentEntity();
        assignment.setId("asg-1");
        assignment.setTermId("term-1");
        assignment.setBookId("book-1");
        assignment.setStatus("PUBLISHED");
        assignment.setQuizRequired(true);
        assignment.setQuizSource(quizSource);
        return assignment;
    }

    private static AssignmentChapterEntity chapterRow(String chapterId, int index) {
        AssignmentChapterEntity row = new AssignmentChapterEntity();
        row.setChapterId(chapterId);
        row.setChapterIndex(index);
        row.setSortOrder(index);
        return row;
    }

    private static ChapterQuizPayload samplePayload(String question) {
        return new ChapterQuizPayload(List.of(
                new ChapterQuizPayload.Question("q1", question, List.of("A", "B"), 0, null, null)
        ));
    }
}
