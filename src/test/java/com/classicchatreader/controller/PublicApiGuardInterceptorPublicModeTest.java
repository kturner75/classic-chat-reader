package com.classicchatreader.controller;

import com.classicchatreader.config.InMemoryIpRateLimiter;
import com.classicchatreader.config.PublicApiGuardInterceptor;
import com.classicchatreader.config.PublicApiGuardMvcConfig;
import com.classicchatreader.service.PreGenerationService;
import com.classicchatreader.service.PreGenerationService.PreGenResult;
import com.classicchatreader.service.PreGenerationJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PreGenerationController.class)
@Import({PublicApiGuardMvcConfig.class, PublicApiGuardInterceptor.class, InMemoryIpRateLimiter.class})
@TestPropertySource(properties = {
        "deployment.mode=public",
        "security.public.api-key=test-key",
        "security.public.rate-limit.window-seconds=60",
        "security.public.rate-limit.generation-requests=1",
        "security.public.rate-limit.chat-requests=1",
        "security.public.rate-limit.authenticated-generation-requests=1",
        "security.public.rate-limit.authenticated-chat-requests=1",
        "generation.cache-only=false"
})
class PublicApiGuardInterceptorPublicModeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreGenerationService preGenerationService;

    @MockitoBean
    private PreGenerationJobService preGenerationJobService;

    @Test
    void sensitiveEndpointWithoutApiKey_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/pregen/book/book-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));

        verifyNoInteractions(preGenerationService);
    }

    @Test
    void sensitiveEndpointWithApiKey_rateLimitsSecondRequest() throws Exception {
        when(preGenerationService.preGenerateForBook("book-1")).thenReturn(successResult("book-1"));

        mockMvc.perform(post("/api/pregen/book/book-1")
                        .header("X-API-Key", "test-key"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/pregen/book/book-1")
                        .header("X-API-Key", "test-key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Rate limit exceeded"));

        verify(preGenerationService, times(1)).preGenerateForBook("book-1");
    }

    private PreGenResult successResult(String bookId) {
        return new PreGenResult(
                true,
                bookId,
                "Book Title",
                "ok",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }
}
