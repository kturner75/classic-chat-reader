package com.classicchatreader.controller;

import jakarta.servlet.http.Cookie;
import com.classicchatreader.config.InMemoryIpRateLimiter;
import com.classicchatreader.config.PublicApiGuardInterceptor;
import com.classicchatreader.config.PublicApiGuardMvcConfig;
import com.classicchatreader.service.PreGenerationService;
import com.classicchatreader.service.PreGenerationService.PreGenResult;
import com.classicchatreader.service.PreGenerationJobService;
import com.classicchatreader.service.PublicSessionAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PreGenerationController.class, AuthController.class})
@Import({PublicApiGuardMvcConfig.class, PublicApiGuardInterceptor.class, InMemoryIpRateLimiter.class, PublicSessionAuthService.class})
@TestPropertySource(properties = {
        "deployment.mode=public",
        "security.public.api-key=",
        "security.public.collaborator.password=secret-password",
        "security.public.session.cookie-name=pdr_collab_session",
        "security.public.session.ttl-minutes=60",
        "security.public.rate-limit.window-seconds=60",
        "security.public.rate-limit.generation-requests=1",
        "security.public.rate-limit.chat-requests=10",
        "security.public.rate-limit.authenticated-generation-requests=1",
        "generation.cache-only=false"
})
class PublicApiGuardInterceptorSessionAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreGenerationService preGenerationService;

    @MockitoBean
    private PreGenerationJobService preGenerationJobService;

    @Test
    void sensitiveEndpoint_withoutAuthSession_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/pregen/book/book-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));

        verifyNoInteractions(preGenerationService);
    }

    @Test
    void sensitiveEndpoint_withValidSessionCookie_isAllowed() throws Exception {
        when(preGenerationService.preGenerateForBook("book-1")).thenReturn(successResult("book-1"));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"password":"secret-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("pdr_collab_session=")))
                .andReturn();

        String token = loginResult.getResponse().getHeader("Set-Cookie")
                .split(";", 2)[0]
                .split("=", 2)[1];

        mockMvc.perform(post("/api/pregen/book/book-1")
                        .cookie(new Cookie("pdr_collab_session", token)))
                .andExpect(status().isOk());

        verify(preGenerationService).preGenerateForBook("book-1");
    }

    @Test
    void rateLimit_isScopedPerCollaboratorSession() throws Exception {
        when(preGenerationService.preGenerateForBook("book-1")).thenReturn(successResult("book-1"));

        String tokenA = loginAndGetToken();
        String tokenB = loginAndGetToken();

        mockMvc.perform(post("/api/pregen/book/book-1")
                        .cookie(new Cookie("pdr_collab_session", tokenA)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/pregen/book/book-1")
                        .cookie(new Cookie("pdr_collab_session", tokenB)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/pregen/book/book-1")
                        .cookie(new Cookie("pdr_collab_session", tokenA)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Rate limit exceeded"));

        verify(preGenerationService, times(2)).preGenerateForBook("book-1");
    }

    private String loginAndGetToken() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"password":"secret-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("pdr_collab_session=")))
                .andReturn();

        return loginResult.getResponse().getHeader("Set-Cookie")
                .split(";", 2)[0]
                .split("=", 2)[1];
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
