package com.classicchatreader.service;

import com.classicchatreader.entity.AssignmentChapterEntity;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.entity.AssignmentQuizEntity;
import com.classicchatreader.model.ChapterQuizGradeResponse;
import com.classicchatreader.model.ChapterQuizPayload;
import com.classicchatreader.model.ChapterQuizViewPayload;
import com.classicchatreader.model.QuizProgress;
import com.classicchatreader.repository.AssignmentQuizRepository;
import com.classicchatreader.repository.AssignmentRepository;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.ParagraphRepository;
import com.classicchatreader.service.llm.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                reasoningProvider,
                objectMapper
        );
    }

    @Test
    void getStudentQuiz_usesCustomPayload() throws Exception {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
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
    void gradeStudentQuiz_recordsAssignmentScopedAttempt() {
        AssignmentEntity assignment = publishedAssignment("CUSTOM");
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
