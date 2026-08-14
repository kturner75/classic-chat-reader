package com.classicchatreader.controller;

import com.classicchatreader.entity.AssignmentChapterEntity;
import com.classicchatreader.entity.AssignmentEntity;
import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ClassroomContextResponse.ClassAssignment;
import com.classicchatreader.model.ClassroomContextResponse.ClassroomFeatureStates;
import com.classicchatreader.model.ClassroomContextResponse.QuizRequirementStatus;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.AssignmentQuizService;
import com.classicchatreader.service.ClassroomAdminService;
import com.classicchatreader.service.ClassroomContextService;
import com.classicchatreader.service.ClassroomTeacherCapabilityService;
import com.classicchatreader.service.ClassroomUsageService;
import com.classicchatreader.service.InviteLinkService;
import com.classicchatreader.service.TeacherQuizAuthoringService;
import com.classicchatreader.service.TeacherStudentOverviewService;
import com.classicchatreader.service.TeacherStudentOverviewService.StudentIdentity;
import com.classicchatreader.service.TeacherStudentOverviewService.StudentOverviewResponse;
import com.classicchatreader.service.TeacherStudentOverviewService.TimeInReaderSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClassroomController.class)
class ClassroomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClassroomContextService classroomContextService;

    @MockitoBean
    private ClassroomAdminService classroomAdminService;

    @MockitoBean
    private InviteLinkService inviteLinkService;

    @MockitoBean
    private AccountAuthService accountAuthService;

    @MockitoBean
    private ClassroomTeacherCapabilityService teacherCapabilityService;

    @MockitoBean
    private TeacherQuizAuthoringService teacherQuizAuthoringService;

    @MockitoBean
    private AssignmentQuizService assignmentQuizService;

    @MockitoBean
    private TeacherStudentOverviewService teacherStudentOverviewService;

    @MockitoBean
    private ClassroomUsageService classroomUsageService;

    @Test
    void getContextReturnsNotEnrolledWhenClassroomDisabled() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.empty());
        when(classroomContextService.getContext(isNull(), isNull())).thenReturn(ClassroomContextResponse.notEnrolled());

        mockMvc.perform(get("/api/classroom/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.assignments").isArray())
                .andExpect(jsonPath("$.assignments").isEmpty());
    }

    @Test
    void getContextReturnsAssignmentsWhenEnrolled() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.empty());
        ClassroomContextResponse response = new ClassroomContextResponse(
                true,
                "lit-101",
                "Literature 101",
                "Ms. Rivera",
                new ClassroomFeatureStates(true, false, true, false, true, true, true, true),
                List.of(
                        new ClassAssignment(
                                "assign-1",
                                "Read Chapters 1-2",
                                "book-1",
                                "Moby Dick",
                                "Herman Melville",
                                "chapter-1",
                                0,
                                "Loomings",
                                "2026-02-20T23:59:00Z",
                                true,
                                QuizRequirementStatus.PENDING,
                                true
                        )
                ),
                "term-1",
                "STUDENT"
        );
        when(classroomContextService.getContext(isNull(), isNull())).thenReturn(response);

        mockMvc.perform(get("/api/classroom/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.classId").value("lit-101"))
                .andExpect(jsonPath("$.className").value("Literature 101"))
                .andExpect(jsonPath("$.termId").value("term-1"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.features.quizEnabled").value(true))
                .andExpect(jsonPath("$.features.recapEnabled").value(false))
                .andExpect(jsonPath("$.assignments[0].assignmentId").value("assign-1"))
                .andExpect(jsonPath("$.assignments[0].quizRequired").value(true))
                .andExpect(jsonPath("$.assignments[0].quizStatus").value("PENDING"));
    }

    @Test
    void getContextForwardsTermIdQueryParam() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.empty());
        when(classroomContextService.getContext(isNull(), eq("term-xyz")))
                .thenReturn(ClassroomContextResponse.notEnrolled());

        mockMvc.perform(get("/api/classroom/context").param("termId", "term-xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false));
    }

    @Test
    void capabilitiesAreDisabledForSignedOutReader() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.empty());
        when(teacherCapabilityService.getCapabilities(isNull()))
                .thenReturn(ClassroomTeacherCapabilityService.TeacherCapabilities.none());

        mockMvc.perform(get("/api/classroom/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canTeach").value(false))
                .andExpect(jsonPath("$.canCreateClass").value(false));
    }

    @Test
    void capabilitiesReflectAuthenticatedTeacher() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.of(
                new AccountAuthService.AccountPrincipal("teacher-1", "teacher@example.test")));
        when(teacherCapabilityService.getCapabilities("teacher-1"))
                .thenReturn(new ClassroomTeacherCapabilityService.TeacherCapabilities(true, true));

        mockMvc.perform(get("/api/classroom/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canTeach").value(true))
                .andExpect(jsonPath("$.canCreateClass").value(true));
    }

    @Test
    void rosterIncludesStudentEmailForTeacherDisplay() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.of(
                new AccountAuthService.AccountPrincipal("teacher-1", "teacher@example.test")));
        when(classroomAdminService.listRoster("teacher-1", "term-1")).thenReturn(List.of(
                new ClassroomAdminService.EnrollmentRow(
                        "enrollment-1",
                        "student-1",
                        "student@example.test",
                        "ACTIVE",
                        LocalDate.of(2026, 8, 24),
                        "Alex Rivera"
                )
        ));

        mockMvc.perform(get("/api/classroom/terms/term-1/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayNameOverride").value("Alex Rivera"))
                .andExpect(jsonPath("$[0].email").value("student@example.test"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void studentOverviewReturnsTeacherDrillDownPayload() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.of(
                new AccountAuthService.AccountPrincipal("teacher-1", "teacher@example.test")));
        when(teacherStudentOverviewService.getOverview("teacher-1", "term-1", "student-1"))
                .thenReturn(new StudentOverviewResponse(
                        "term-1",
                        new StudentIdentity("student-1", "student@example.test", "Alex Rivera", "2026-08-24"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new TimeInReaderSummary(
                                "Approximate time in reader",
                                "Engagement proxy",
                                60000L,
                                List.of()),
                        "Pilot teacher drill-down"
                ));

        mockMvc.perform(get("/api/classroom/terms/term-1/students/student-1/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.student.email").value("student@example.test"))
                .andExpect(jsonPath("$.timeInReader.approximateTotalMs").value(60000))
                .andExpect(jsonPath("$.ferpaNote").exists());
    }

    @Test
    void createAssignmentAcceptsTeacherWorkspacePayload() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.of(
                new AccountAuthService.AccountPrincipal("teacher-1", "teacher@example.test")));
        AssignmentEntity saved = new AssignmentEntity();
        saved.setId("assign-1");
        saved.setTermId("term-1");
        saved.setTitle("Read Chapter 1");
        saved.setBookId("book-1");
        AssignmentChapterEntity chapter = new AssignmentChapterEntity();
        chapter.setChapterId("chapter-1");
        chapter.setChapterIndex(0);
        chapter.setSortOrder(0);
        saved.replaceChapters(List.of(chapter));
        saved.setStatus("DRAFT");
        when(classroomAdminService.createAssignment(eq("teacher-1"), eq("term-1"), any()))
                .thenReturn(saved);

        mockMvc.perform(post("/api/classroom/terms/term-1/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Read Chapter 1",
                                  "bookId": "book-1",
                                  "chapterId": "chapter-1",
                                  "chapterIndex": 0,
                                  "dueDate": null,
                                  "availableFromDate": null,
                                  "quizRequired": false,
                                  "characterChatRequired": false,
                                  "status": "DRAFT",
                                  "clearQuizPassRules": true,
                                  "quizPassMinCorrect": null,
                                  "quizMaxRetries": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value("assign-1"))
                .andExpect(jsonPath("$.title").value("Read Chapter 1"))
                .andExpect(jsonPath("$.chapters[0].chapterId").value("chapter-1"));

        ArgumentCaptor<ClassroomAdminService.AssignmentWriteRequest> captor =
                ArgumentCaptor.forClass(ClassroomAdminService.AssignmentWriteRequest.class);
        verify(classroomAdminService).createAssignment(eq("teacher-1"), eq("term-1"), captor.capture());
        ClassroomAdminService.AssignmentWriteRequest body = captor.getValue();
        assertEquals("Read Chapter 1", body.title());
        assertEquals("book-1", body.bookId());
        assertEquals("chapter-1", body.chapterId());
        assertEquals(0, body.chapterIndex());
        assertFalse(body.quizRequired());
        assertNull(body.quizPassMinCorrect());
    }

    @Test
    void deleteDraftAssignmentReturnsNoContent() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.of(
                new AccountAuthService.AccountPrincipal("teacher-1", "teacher@example.test")));

        mockMvc.perform(delete("/api/classroom/assignments/assign-1"))
                .andExpect(status().isNoContent());

        verify(classroomAdminService).deleteDraftAssignment("teacher-1", "assign-1");
    }

    @Test
    void studentOverviewForbiddenForNonTeacher() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.of(
                new AccountAuthService.AccountPrincipal("student-2", "other@example.test")));
        when(teacherStudentOverviewService.getOverview("student-2", "term-1", "student-1"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher access required."));

        mockMvc.perform(get("/api/classroom/terms/term-1/students/student-1/overview"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignmentEffectiveQuizAndSaveUseAssignmentQuizService() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.of(
                new AccountAuthService.AccountPrincipal("teacher-1", "teacher@example.test")));
        AssignmentQuizService.AssignmentEffectiveQuizResponse payload =
                new AssignmentQuizService.AssignmentEffectiveQuizResponse(
                        "asg-1",
                        "term-1",
                        "book-1",
                        "CHAPTER",
                        true,
                        List.of(),
                        "v1"
                );
        when(assignmentQuizService.getEffectiveQuiz("teacher-1", "asg-1")).thenReturn(payload);
        when(assignmentQuizService.saveCustomQuiz(eq("teacher-1"), eq("asg-1"), any())).thenReturn(
                new AssignmentQuizService.AssignmentEffectiveQuizResponse(
                        "asg-1", "term-1", "book-1", "CUSTOM", true, List.of(), "v2"));

        mockMvc.perform(get("/api/classroom/assignments/asg-1/effective-quiz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizSource").value("CHAPTER"))
                .andExpect(jsonPath("$.chapterDefaultAvailable").value(true));

        mockMvc.perform(put("/api/classroom/assignments/asg-1/quiz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"questions":[{"id":"q1","question":"Who?","options":["A","B"],"correctOptionIndex":0}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizSource").value("CUSTOM"));
    }
}
