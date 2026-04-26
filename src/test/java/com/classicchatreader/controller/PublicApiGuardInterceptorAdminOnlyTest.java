package com.classicchatreader.controller;

import jakarta.servlet.http.Cookie;
import com.classicchatreader.config.InMemoryIpRateLimiter;
import com.classicchatreader.config.PublicApiGuardInterceptor;
import com.classicchatreader.config.PublicApiGuardMvcConfig;
import com.classicchatreader.model.Book;
import com.classicchatreader.service.BookCoverService;
import com.classicchatreader.service.BookStorageService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.ParagraphAnnotationService;
import com.classicchatreader.service.PublicSessionAuthService;
import com.classicchatreader.service.ReaderIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({LibraryController.class, AuthController.class})
@Import({PublicApiGuardMvcConfig.class, PublicApiGuardInterceptor.class, InMemoryIpRateLimiter.class, PublicSessionAuthService.class})
@TestPropertySource(properties = {
        "deployment.mode=public",
        "security.public.api-key=test-key",
        "security.public.collaborator.password=secret-password",
        "security.public.session.cookie-name=pdr_collab_session",
        "security.public.session.ttl-minutes=60",
        "security.public.rate-limit.window-seconds=60",
        "security.public.rate-limit.generation-requests=10",
        "security.public.rate-limit.chat-requests=10",
        "security.public.rate-limit.authenticated-generation-requests=10",
        "security.public.rate-limit.authenticated-chat-requests=10"
})
class PublicApiGuardInterceptorAdminOnlyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookStorageService bookStorageService;

    @MockitoBean
    private BookCoverService bookCoverService;

    @MockitoBean
    private CdnAssetService cdnAssetService;

    @MockitoBean
    private ParagraphAnnotationService paragraphAnnotationService;

    @MockitoBean
    private ReaderIdentityService readerIdentityService;

    @Test
    void adminEndpoint_withoutApiKey_returnsUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/library/book-1/features")
                        .contentType("application/json")
                        .content("{\"ttsEnabled\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Admin API key required"));

        verifyNoInteractions(bookStorageService);
    }

    @Test
    void adminEndpoint_withCollaboratorSessionOnly_returnsUnauthorized() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(patch("/api/library/book-1/features")
                        .cookie(new Cookie("pdr_collab_session", token))
                        .contentType("application/json")
                        .content("{\"ttsEnabled\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Admin API key required"));

        verifyNoInteractions(bookStorageService);
    }

    @Test
    void adminEndpoint_withApiKey_allowsFeatureUpdate() throws Exception {
        Book updated = new Book(
                "book-1",
                "Book Title",
                "Author",
                "",
                null,
                List.of(),
                true,
                false,
                true
        );
        when(bookStorageService.updateBookFeatures("book-1", true, false, true))
                .thenReturn(Optional.of(updated));

        mockMvc.perform(patch("/api/library/book-1/features")
                        .header("X-API-Key", "test-key")
                        .contentType("application/json")
                        .content("{\"ttsEnabled\":true,\"illustrationEnabled\":false,\"characterEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("book-1"))
                .andExpect(jsonPath("$.ttsEnabled").value(true))
                .andExpect(jsonPath("$.illustrationEnabled").value(false))
                .andExpect(jsonPath("$.characterEnabled").value(true));

        verify(bookStorageService).updateBookFeatures("book-1", true, false, true);
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
}
