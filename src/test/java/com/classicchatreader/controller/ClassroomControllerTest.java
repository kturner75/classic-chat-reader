package com.classicchatreader.controller;

import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ClassroomContextResponse.ClassAssignment;
import com.classicchatreader.model.ClassroomContextResponse.ClassroomFeatureStates;
import com.classicchatreader.model.ClassroomContextResponse.QuizRequirementStatus;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.ClassroomAdminService;
import com.classicchatreader.service.ClassroomContextService;
import com.classicchatreader.service.ClassroomTeacherCapabilityService;
import com.classicchatreader.service.InviteLinkService;
import com.classicchatreader.service.TeacherQuizAuthoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
