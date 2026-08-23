package com.classicchatreader.controller;

import com.classicchatreader.config.InMemoryIpRateLimiter;
import com.classicchatreader.config.PublicApiGuardInterceptor;
import com.classicchatreader.config.PublicApiGuardMvcConfig;
import com.classicchatreader.service.PreGenerationJobService;
import com.classicchatreader.service.PreGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PreGenerationController.class)
@Import({PublicApiGuardMvcConfig.class, PublicApiGuardInterceptor.class, InMemoryIpRateLimiter.class})
@TestPropertySource(properties = {
        "deployment.mode= public\r",
        "security.public.api-key=test-key",
        "security.public.rate-limit.window-seconds=60",
        "security.public.rate-limit.generation-requests=1",
        "security.public.rate-limit.chat-requests=1",
        "generation.cache-only=false"
})
class PublicApiGuardInterceptorPaddedModeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreGenerationService preGenerationService;

    @MockitoBean
    private PreGenerationJobService preGenerationJobService;

    @Test
    void paddedPublicMode_stillRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/pregen/book/book-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));

        verifyNoInteractions(preGenerationService);
    }
}
