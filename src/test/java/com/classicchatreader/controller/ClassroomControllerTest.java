package com.classicchatreader.controller;

import com.classicchatreader.model.ClassroomContextResponse;
import com.classicchatreader.model.ClassroomContextResponse.ClassAssignment;
import com.classicchatreader.model.ClassroomContextResponse.ClassroomFeatureStates;
import com.classicchatreader.model.ClassroomContextResponse.QuizRequirementStatus;
import com.classicchatreader.service.ClassroomContextService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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

    @Test
    void getContextReturnsNotEnrolledWhenClassroomDisabled() throws Exception {
        when(classroomContextService.getContext()).thenReturn(ClassroomContextResponse.notEnrolled());

        mockMvc.perform(get("/api/classroom/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.assignments").isArray())
                .andExpect(jsonPath("$.assignments").isEmpty());
    }

    @Test
    void getContextReturnsAssignmentsWhenEnrolled() throws Exception {
        ClassroomContextResponse response = new ClassroomContextResponse(
                true,
                "lit-101",
                "Literature 101",
                "Ms. Rivera",
                new ClassroomFeatureStates(true, false, true, false, true, true, true),
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
                )
        );
        when(classroomContextService.getContext()).thenReturn(response);

        mockMvc.perform(get("/api/classroom/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.classId").value("lit-101"))
                .andExpect(jsonPath("$.className").value("Literature 101"))
                .andExpect(jsonPath("$.features.quizEnabled").value(true))
                .andExpect(jsonPath("$.features.recapEnabled").value(false))
                .andExpect(jsonPath("$.assignments[0].assignmentId").value("assign-1"))
                .andExpect(jsonPath("$.assignments[0].quizRequired").value(true))
                .andExpect(jsonPath("$.assignments[0].quizStatus").value("PENDING"));
    }
}
