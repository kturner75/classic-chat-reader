package com.classicchatreader.controller;

import com.classicchatreader.service.PreGenerationService;
import com.classicchatreader.service.PreGenerationJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PreGenerationController.class)
@TestPropertySource(properties = {
        "generation.cache-only=true"
})
class PreGenerationControllerCacheOnlyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreGenerationService preGenerationService;

    @MockitoBean
    private PreGenerationJobService preGenerationJobService;

    @Test
    void preGenerateForBook_cacheOnly_returnsConflict() throws Exception {
        mockMvc.perform(post("/api/pregen/book/book-1"))
                .andExpect(status().isConflict());

        verifyNoInteractions(preGenerationService);
    }

    @Test
    void preGenerateByGutenbergId_cacheOnly_returnsConflict() throws Exception {
        mockMvc.perform(post("/api/pregen/gutenberg/1234"))
                .andExpect(status().isConflict());

        verifyNoInteractions(preGenerationService, preGenerationJobService);
    }

    @Test
    void startJobForBook_cacheOnly_returnsConflict() throws Exception {
        mockMvc.perform(post("/api/pregen/jobs/book/book-1"))
                .andExpect(status().isConflict());

        verifyNoInteractions(preGenerationService, preGenerationJobService);
    }
}
