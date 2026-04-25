package com.classicchatreader.controller;

import jakarta.servlet.http.Cookie;
import com.classicchatreader.service.PublicSessionAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(PublicSessionAuthService.class)
@TestPropertySource(properties = {
        "deployment.mode=public",
        "security.public.collaborator.password=secret-password",
        "security.public.session.cookie-name=pdr_collab_session",
        "security.public.session.ttl-minutes=60"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void status_publicMode_startsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicMode", is(true)))
                .andExpect(jsonPath("$.authRequired", is(true)))
                .andExpect(jsonPath("$.authenticated", is(false)))
                .andExpect(jsonPath("$.canAccessSensitive", is(false)));
    }

    @Test
    void login_validPassword_setsCookie_andStatusBecomesAuthenticated() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"password":"secret-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("pdr_collab_session=")))
                .andReturn();

        String token = loginResult.getResponse().getHeader("Set-Cookie")
                .split(";", 2)[0]
                .split("=", 2)[1];

        mockMvc.perform(get("/api/auth/status").cookie(new Cookie("pdr_collab_session", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated", is(true)))
                .andExpect(jsonPath("$.canAccessSensitive", is(true)));
    }

    @Test
    void login_invalidPassword_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Invalid password.")));
    }
}
