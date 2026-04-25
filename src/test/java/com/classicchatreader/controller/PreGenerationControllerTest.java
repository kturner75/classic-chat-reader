package com.classicchatreader.controller;

import com.classicchatreader.service.PreGenerationService;
import com.classicchatreader.service.PreGenerationService.PreGenResult;
import com.classicchatreader.service.PreGenerationJobService;
import com.classicchatreader.service.PreGenerationJobService.JobState;
import com.classicchatreader.service.PreGenerationJobService.JobType;
import com.classicchatreader.service.PreGenerationJobService.PreGenJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;

@WebMvcTest(PreGenerationController.class)
@TestPropertySource(properties = {
        "generation.cache-only=false"
})
class PreGenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreGenerationService preGenerationService;

    @MockitoBean
    private PreGenerationJobService preGenerationJobService;

    @Test
    void preGenerateForBook_success_returnsOk() throws Exception {
        PreGenResult result = new PreGenResult(
                true,
                "book-1",
                "Pride and Prejudice",
                "Generation complete",
                12,
                12,
                0,
                7,
                0,
                12,
                0,
                12,
                7,
                12
        );
        when(preGenerationService.preGenerateForBook("book-1")).thenReturn(result);

        mockMvc.perform(post("/api/pregen/book/book-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.chaptersProcessed", is(12)))
                .andExpect(jsonPath("$.newRecaps", is(12)));
    }

    @Test
    void preGenerateByGutenbergId_failure_returnsBadRequest() throws Exception {
        when(preGenerationService.preGenerateByGutenbergId(1234))
                .thenReturn(PreGenResult.failure("Import failed: timed out"));

        mockMvc.perform(post("/api/pregen/gutenberg/1234"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", is("Import failed: timed out")));
    }

    @Test
    void startJobForBook_returnsAccepted() throws Exception {
        PreGenJobStatus job = new PreGenJobStatus(
                "job-1",
                JobType.BOOK,
                JobState.QUEUED,
                "book-1",
                null,
                false,
                LocalDateTime.now(),
                null,
                null,
                "Job queued",
                null,
                null,
                null
        );
        when(preGenerationJobService.startBookJob("book-1")).thenReturn(job);

        mockMvc.perform(post("/api/pregen/jobs/book/book-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId", is("job-1")))
                .andExpect(jsonPath("$.state", is("QUEUED")))
                .andExpect(jsonPath("$.bookId", is("book-1")));
    }

    @Test
    void getJobStatus_unknownJob_returnsNotFound() throws Exception {
        when(preGenerationJobService.getJobStatus("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/pregen/jobs/missing"))
                .andExpect(status().isNotFound());
    }
}
