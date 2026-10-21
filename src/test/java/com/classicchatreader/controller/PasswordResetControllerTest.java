package com.classicchatreader.controller;

import com.classicchatreader.service.AccountAuthAuditService;
import com.classicchatreader.service.AccountAuthRateLimiter;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PasswordResetController.class)
class PasswordResetControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PasswordResetService passwordResetService;
    @MockitoBean private AccountAuthService accountAuthService;
    @MockitoBean private AccountAuthRateLimiter accountAuthRateLimiter;
    @MockitoBean private AccountAuthAuditService accountAuthAuditService;

    @Test
    void requestAnswersTheSameWhetherOrNotTheAccountExists() throws Exception {
        when(accountAuthService.isAccountAuthEnabled()).thenReturn(true);
        when(accountAuthRateLimiter.checkPasswordResetRequest(any(), eq("anyone@school.edu")))
                .thenReturn(AccountAuthRateLimiter.RateLimitResult.permitted());

        mockMvc.perform(post("/api/account/password-reset/request")
                        .contentType("application/json")
                        .content("{\"email\":\"anyone@school.edu\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message", is(PasswordResetController.REQUEST_ACCEPTED_MESSAGE)));
        verify(passwordResetService).requestReset("anyone@school.edu");
    }

    @Test
    void rateLimitedRequestSendsNothing() throws Exception {
        when(accountAuthService.isAccountAuthEnabled()).thenReturn(true);
        when(accountAuthRateLimiter.checkPasswordResetRequest(any(), eq("anyone@school.edu")))
                .thenReturn(AccountAuthRateLimiter.RateLimitResult.limited(60, "email"));

        mockMvc.perform(post("/api/account/password-reset/request")
                        .contentType("application/json")
                        .content("{\"email\":\"anyone@school.edu\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
        verify(passwordResetService, never()).requestReset(any());
    }

    @Test
    void spentLinkIsGone() throws Exception {
        when(accountAuthService.isAccountAuthEnabled()).thenReturn(true);
        when(accountAuthRateLimiter.checkPasswordResetToken(any()))
                .thenReturn(AccountAuthRateLimiter.RateLimitResult.permitted());
        when(passwordResetService.completeReset(eq("old-token"), eq("new-password-1"), any()))
                .thenReturn(AccountAuthService.AuthResult.error(
                        AccountAuthService.ResultStatus.INVALID_CREDENTIALS, true, "This reset link is invalid or has expired."));

        mockMvc.perform(post("/api/account/password-reset/complete")
                        .contentType("application/json")
                        .content("{\"token\":\"old-token\",\"password\":\"new-password-1\"}"))
                .andExpect(status().isGone());
    }

    @Test
    void successfulResetReturnsOk() throws Exception {
        when(accountAuthService.isAccountAuthEnabled()).thenReturn(true);
        when(accountAuthRateLimiter.checkPasswordResetToken(any()))
                .thenReturn(AccountAuthRateLimiter.RateLimitResult.permitted());
        when(passwordResetService.completeReset(eq("live-token"), eq("new-password-1"), any()))
                .thenReturn(AccountAuthService.AuthResult.success(true, "student@columbiastate.edu", "Your password has been reset."));

        mockMvc.perform(post("/api/account/password-reset/complete")
                        .contentType("application/json")
                        .content("{\"token\":\"live-token\",\"password\":\"new-password-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Your password has been reset.")));
    }
}
