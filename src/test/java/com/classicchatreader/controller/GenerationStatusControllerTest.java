package com.classicchatreader.controller;

import com.classicchatreader.model.GenerationJobStatusResponse;
import com.classicchatreader.model.GenerationPipelineStatus;
import com.classicchatreader.service.GenerationJobStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GenerationStatusController.class)
class GenerationStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerationJobStatusService generationJobStatusService;

    @Test
    void getGlobalStatus_returnsAggregatedJobSnapshot() throws Exception {
        GenerationJobStatusResponse response = new GenerationJobStatusResponse(
                "global",
                null,
                LocalDateTime.of(2026, 2, 14, 12, 0),
                GenerationPipelineStatus.of(1, 2, 3, 4, 5),
                GenerationPipelineStatus.of(6, 7, 8, 9, 10),
                GenerationPipelineStatus.of(11, 12, 13, 14, 15),
                GenerationPipelineStatus.of(16, 17, 18, 19, 20),
                GenerationPipelineStatus.of(34, 38, 42, 46, 50)
        );
        when(generationJobStatusService.getGlobalStatus()).thenReturn(response);

        mockMvc.perform(get("/api/generation/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope", is("global")))
                .andExpect(jsonPath("$.illustrations.retryScheduled", is(2)))
                .andExpect(jsonPath("$.portraits.pending", is(6)))
                .andExpect(jsonPath("$.totals.failed", is(50)));
    }

    @Test
    void getBookStatus_returnsBookScopedSnapshot() throws Exception {
        GenerationJobStatusResponse response = new GenerationJobStatusResponse(
                "book",
                "book-1",
                LocalDateTime.of(2026, 2, 14, 12, 1),
                GenerationPipelineStatus.of(1, 0, 1, 2, 0),
                GenerationPipelineStatus.of(0, 1, 1, 3, 1),
                GenerationPipelineStatus.of(2, 0, 1, 4, 0),
                GenerationPipelineStatus.of(3, 2, 0, 2, 1),
                GenerationPipelineStatus.of(6, 3, 3, 11, 2)
        );
        when(generationJobStatusService.getBookStatus("book-1")).thenReturn(response);

        mockMvc.perform(get("/api/generation/book/book-1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope", is("book")))
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.recaps.retryScheduled", is(2)))
                .andExpect(jsonPath("$.totals.completed", is(11)));
    }
}
