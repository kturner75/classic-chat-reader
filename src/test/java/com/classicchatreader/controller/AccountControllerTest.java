package com.classicchatreader.controller;

import com.classicchatreader.model.AccountStateSnapshot;
import com.classicchatreader.service.AccountAuthAuditService;
import com.classicchatreader.service.AccountAuthRateLimiter;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.AccountClaimSyncService;
import com.classicchatreader.service.AccountMetricsService;
import com.classicchatreader.service.GoogleAccountOAuthService;
import com.classicchatreader.service.ReaderProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountAuthService accountAuthService;

    @MockitoBean
    private ReaderProfileService readerProfileService;

    @MockitoBean
    private AccountClaimSyncService accountClaimSyncService;

    @MockitoBean
    private AccountMetricsService accountMetricsService;

    @MockitoBean
    private AccountAuthRateLimiter accountAuthRateLimiter;

    @MockitoBean
    private AccountAuthAuditService accountAuthAuditService;

    @MockitoBean
    private GoogleAccountOAuthService googleAccountOAuthService;

    @MockitoBean
    private com.classicchatreader.service.AccountDataExportService accountDataExportService;

    @MockitoBean
    private com.classicchatreader.service.AccountDeletionService accountDeletionService;

    private void signedIn() {
        when(accountAuthService.resolveAuthenticatedPrincipal(any()))
                .thenReturn(java.util.Optional.of(new AccountAuthService.AccountPrincipal("user-1", "reader@example.com")));
    }

    private org.springframework.test.web.servlet.ResultActions deleteWith(String json) throws Exception {
        return mockMvc.perform(post("/api/account/delete").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void deleteAccountRequiresSignInAndExplicitConfirmation() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(java.util.Optional.empty());
        deleteWith("{\"confirm\":true}").andExpect(status().isUnauthorized());
        signedIn();
        deleteWith("{\"email\":\"reader@example.com\"}").andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(accountDeletionService);
    }

    @Test
    void deleteAccountRefusesFailedReauthenticationWithoutDeleting() throws Exception {
        signedIn();
        when(accountAuthService.confirmAccountOwner("user-1", "reader@example.com", "wrong"))
                .thenReturn(AccountAuthService.AuthResult.error(AccountAuthService.ResultStatus.INVALID_CREDENTIALS, true, "Invalid email or password."));
        deleteWith("{\"confirm\":true,\"email\":\"reader@example.com\",\"password\":\"wrong\"}")
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(accountDeletionService);
    }

    @Test
    void deleteAccountReportsLockoutWithRetryAfter() throws Exception {
        signedIn();
        when(accountAuthService.confirmAccountOwner(any(), any(), any()))
                .thenReturn(AccountAuthService.AuthResult.error(AccountAuthService.ResultStatus.ACCOUNT_LOCKED, true, "Too many failed sign-in attempts.", 120));
        deleteWith("{\"confirm\":true,\"email\":\"reader@example.com\",\"password\":\"x\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"));
        org.mockito.Mockito.verifyNoInteractions(accountDeletionService);
    }

    @Test
    void deleteAccountReportsTeacherAccountsAsConflictAndKeepsTheSession() throws Exception {
        signedIn();
        when(accountAuthService.confirmAccountOwner(any(), any(), any()))
                .thenReturn(AccountAuthService.AuthResult.success(true, "reader@example.com", "Confirmed."));
        when(accountDeletionService.delete("user-1")).thenThrow(new IllegalStateException("This account teaches or manages classes."));
        deleteWith("{\"confirm\":true,\"email\":\"reader@example.com\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This account teaches or manages classes."));
        verify(accountAuthService, org.mockito.Mockito.never()).clearSessionCookie(any());
    }

    @Test
    void deleteAccountDeletesAndClearsTheSessionCookie() throws Exception {
        signedIn();
        when(accountAuthService.confirmAccountOwner("user-1", "reader@example.com", "pw"))
                .thenReturn(AccountAuthService.AuthResult.success(true, "reader@example.com", "Confirmed."));
        when(accountDeletionService.delete("user-1"))
                .thenReturn(new com.classicchatreader.service.AccountDeletionService.DeletionResult("deleted:abc", java.util.Map.of()));
        deleteWith("{\"confirm\":true,\"email\":\"reader@example.com\",\"password\":\"pw\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        verify(accountDeletionService).delete("user-1");
        verify(accountAuthService).clearSessionCookie(any());
        verify(accountAuthAuditService).record(eq("account_delete"), eq("success"), any(), eq("reader@example.com"), eq("deleted:abc"), isNull(), isNull());
    }

    @Test
    void deletePreviewRequiresSignIn() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/account/delete-preview")).andExpect(status().isUnauthorized());
    }

    @Test
    void exportMyDataRequiresSignIn() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/account/export")).andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(accountDataExportService);
    }

    @Test
    void exportMyDataServesTheSignedInAccountsFileAndDeletesIt() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any()))
                .thenReturn(java.util.Optional.of(new AccountAuthService.AccountPrincipal("user-1", "reader@example.com")));
        when(accountDataExportService.accountExists("user-1")).thenReturn(true);
        java.nio.file.Path file = java.nio.file.Files.createTempFile("export-test-", ".json");
        java.nio.file.Files.writeString(file, "{\"account\":{}}");
        when(accountDataExportService.exportToTempFile("user-1")).thenReturn(file);

        mockMvc.perform(get("/api/account/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"classic-chat-reader-my-data.json\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Length", "14"))
                .andExpect(jsonPath("$.account").exists());
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(file), "the temp export is deleted once served");
    }

    @Test
    void exportMyDataReturns429WhileAnotherExportIsBeingPrepared() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any()))
                .thenReturn(java.util.Optional.of(new AccountAuthService.AccountPrincipal("user-1", "reader@example.com")));
        when(accountDataExportService.accountExists("user-1")).thenReturn(true);
        when(accountDataExportService.exportToTempFile("user-1"))
                .thenThrow(new com.classicchatreader.service.AccountDataExportService.ExportBusyException("Your data export is already being prepared."));
        mockMvc.perform(get("/api/account/export"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.error").value("Your data export is already being prepared."));
    }

    @Test
    void exportMyDataReturns404WhenTheAccountIsGone() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any()))
                .thenReturn(java.util.Optional.of(new AccountAuthService.AccountPrincipal("user-1", "reader@example.com")));
        when(accountDataExportService.accountExists("user-1")).thenReturn(false);
        mockMvc.perform(get("/api/account/export")).andExpect(status().isNotFound());
        verify(accountDataExportService, org.mockito.Mockito.never()).exportToTempFile(any());
    }

    @Test
    void status_returnsUnauthenticatedWhenNoSession() throws Exception {
        when(accountAuthService.status(any()))
                .thenReturn(new AccountAuthService.AuthResult(
                        AccountAuthService.ResultStatus.SUCCESS,
                        true,
                        false,
                        null,
                        null
                ));
        when(googleAccountOAuthService.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/account/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountAuthEnabled", is(true)))
                .andExpect(jsonPath("$.authenticated", is(false)))
                .andExpect(jsonPath("$.googleAuthEnabled", is(true)));
    }

    @Test
    void register_missingFields_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/account/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"","password":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Email and password are required.")));
    }

    @Test
    void register_emailAlreadyExists_returnsConflict() throws Exception {
        allowRegisterAndLoginRateLimits();
        when(accountAuthService.register(eq("reader@example.com"), eq("password123"), any()))
                .thenReturn(new AccountAuthService.AuthResult(
                        AccountAuthService.ResultStatus.EMAIL_ALREADY_EXISTS,
                        true,
                        false,
                        null,
                        "Email is already registered."
                ));

        mockMvc.perform(post("/api/account/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"reader@example.com","password":"password123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Email is already registered.")));

        verify(accountAuthAuditService).record(eq("register"), eq("email_exists"), any(), eq("reader@example.com"), isNull(), isNull(), isNull());
    }

    @Test
    void register_rolloutRestricted_returnsForbidden() throws Exception {
        allowRegisterAndLoginRateLimits();
        when(accountAuthService.register(eq("reader@example.com"), eq("password123"), any()))
                .thenReturn(new AccountAuthService.AuthResult(
                        AccountAuthService.ResultStatus.ROLLOUT_RESTRICTED,
                        true,
                        false,
                        null,
                        "Account access is currently limited to internal rollout users."
                ));

        mockMvc.perform(post("/api/account/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"reader@example.com","password":"password123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is("Account access is currently limited to internal rollout users.")));

        verify(accountAuthAuditService).record(eq("register"), eq("rollout_restricted"), any(), eq("reader@example.com"), isNull(), isNull(), isNull());
    }

    @Test
    void login_invalidCredentials_returnsUnauthorized() throws Exception {
        allowRegisterAndLoginRateLimits();
        when(accountAuthService.login(eq("reader@example.com"), eq("wrong-password"), any()))
                .thenReturn(new AccountAuthService.AuthResult(
                        AccountAuthService.ResultStatus.INVALID_CREDENTIALS,
                        true,
                        false,
                        null,
                        "Invalid email or password."
                ));

        mockMvc.perform(post("/api/account/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"reader@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Invalid email or password.")));
    }

    @Test
    void register_whenAuthDisabled_returnsServiceUnavailable() throws Exception {
        allowRegisterAndLoginRateLimits();
        when(accountAuthService.register(eq("reader@example.com"), eq("password123"), any()))
                .thenReturn(new AccountAuthService.AuthResult(
                        AccountAuthService.ResultStatus.DISABLED,
                        false,
                        false,
                        null,
                        "Account auth is disabled."
                ));

        mockMvc.perform(post("/api/account/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"reader@example.com","password":"password123"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.accountAuthEnabled", is(false)));
    }

    @Test
    void register_rateLimited_returnsTooManyRequestsAndRetryAfter() throws Exception {
        when(accountAuthRateLimiter.checkRegister(any(), eq("reader@example.com")))
                .thenReturn(AccountAuthRateLimiter.RateLimitResult.limited(45, "ip"));

        mockMvc.perform(post("/api/account/register")
                        .contentType("application/json")
                        .content("""
                                {"email":"reader@example.com","password":"password123"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.message", is("Too many account auth attempts. Please retry shortly.")));

        verify(accountAuthAuditService).record(eq("register"), eq("rate_limited"), any(), eq("reader@example.com"), isNull(), eq(45), eq("ip"));
    }

    @Test
    void login_accountLocked_returnsTooManyRequestsAndRetryAfter() throws Exception {
        allowRegisterAndLoginRateLimits();
        when(accountAuthService.login(eq("reader@example.com"), eq("wrong-password"), any()))
                .thenReturn(new AccountAuthService.AuthResult(
                        AccountAuthService.ResultStatus.ACCOUNT_LOCKED,
                        true,
                        false,
                        null,
                        "Too many failed sign-in attempts. Please try again later.",
                        30
                ));

        mockMvc.perform(post("/api/account/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"reader@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.message", is("Too many failed sign-in attempts. Please try again later.")));
    }

    @Test
    void googleStart_redirectsToProvider() throws Exception {
        when(googleAccountOAuthService.beginAuthorization(eq("/books/1"), any()))
                .thenReturn(new GoogleAccountOAuthService.AuthorizationStartResult(
                        URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=abc"),
                        true
                ));

        mockMvc.perform(get("/api/account/google/start").param("returnTo", "/books/1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://accounts.google.com/o/oauth2/v2/auth?state=abc"));

        verify(accountAuthAuditService).record(eq("google_start"), eq("redirected"), any(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void googleCallback_failureRedirectsBackToApp() throws Exception {
        when(googleAccountOAuthService.completeAuthorization(eq(null), eq(null), eq("access_denied"), any(), any()))
                .thenReturn(new GoogleAccountOAuthService.AuthorizationCallbackResult(
                        URI.create("/?account_notice=google_cancelled"),
                        false,
                        "cancelled",
                        null,
                        null
                ));

        mockMvc.perform(get("/api/account/google/callback").param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/?account_notice=google_cancelled"));

        verify(accountAuthAuditService).record(eq("google_callback"), eq("failure"), any(), isNull(), isNull(), isNull(), eq("cancelled"));
    }

    @Test
    void googleLink_missingPassword_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/account/google/link")
                        .contentType("application/json")
                        .content("""
                                {"password":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Password is required to link Google.")));

        verify(accountAuthAuditService).record(eq("google_link"), eq("invalid_request"), any(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void googleLink_passwordReAuth_returnsSuccess() throws Exception {
        allowRegisterAndLoginRateLimits();
        when(accountAuthService.pendingExternalIdentityLinkEmail(any()))
                .thenReturn(java.util.Optional.of("reader@example.com"));
        when(accountAuthService.confirmExternalIdentityLink(eq("password123"), any(), any()))
                .thenReturn(new AccountAuthService.AuthResult(
                        AccountAuthService.ResultStatus.SUCCESS,
                        true,
                        true,
                        "reader@example.com",
                        "Google is linked to your account."
                ));

        mockMvc.perform(post("/api/account/google/link")
                        .contentType("application/json")
                        .content("""
                                {"password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated", is(true)))
                .andExpect(jsonPath("$.email", is("reader@example.com")));

        verify(accountAuthAuditService).record(
                eq("google_link"),
                eq("success"),
                any(),
                eq("reader@example.com"),
                isNull(),
                isNull(),
                isNull()
        );
    }

    @Test
    void googleCallback_linkRequired_recordsLinkRequiredOutcome() throws Exception {
        when(googleAccountOAuthService.completeAuthorization(eq("code"), eq("state"), isNull(), any(), any()))
                .thenReturn(new GoogleAccountOAuthService.AuthorizationCallbackResult(
                        URI.create("/?account_notice=google_link_required"),
                        false,
                        "link_required",
                        "reader@example.com",
                        AccountAuthService.ResultStatus.EXTERNAL_IDENTITY_LINK_REQUIRED
                ));

        mockMvc.perform(get("/api/account/google/callback")
                        .param("code", "code")
                        .param("state", "state"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/?account_notice=google_link_required"));

        verify(accountAuthAuditService).record(
                eq("google_callback"),
                eq("link_required"),
                any(),
                eq("reader@example.com"),
                isNull(),
                isNull(),
                eq("link_required")
        );
    }

    @Test
    void claimSync_requiresAuthenticatedAccount() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/account/claim-sync")
                        .contentType("application/json")
                        .content("""
                                {"state":{"favoriteBookIds":["book-1"]}}
                                """))
                .andExpect(status().isUnauthorized());

        verify(accountAuthAuditService).record(eq("claim_sync"), eq("unauthorized"), any(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void claimSync_returnsMergedState() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any()))
                .thenReturn(java.util.Optional.of(new AccountAuthService.AccountPrincipal("user-1", "reader@example.com")));
        when(readerProfileService.resolveReaderId(any(), any())).thenReturn("reader-cookie-1");
        when(accountClaimSyncService.claimAndSync(eq("user-1"), eq("reader-cookie-1"), any()))
                .thenReturn(new AccountClaimSyncService.ClaimSyncResult(
                        true,
                        new AccountStateSnapshot(
                                java.util.List.of("book-1"),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of("book-1", true)
                        )
                ));

        mockMvc.perform(post("/api/account/claim-sync")
                        .contentType("application/json")
                        .content("""
                                {"state":{"favoriteBookIds":["book-1"],"recapOptOut":{"book-1":true}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimApplied", is(true)))
                .andExpect(jsonPath("$.state.favoriteBookIds[0]", is("book-1")))
                .andExpect(jsonPath("$.state.recapOptOut.book-1", is(true)));

        verify(accountAuthAuditService).record(eq("claim_sync"), eq("success_claim_applied"), any(), eq("reader@example.com"), eq("user-1"), isNull(), isNull());
    }

    private void allowRegisterAndLoginRateLimits() {
        when(accountAuthRateLimiter.checkRegister(any(), any())).thenReturn(AccountAuthRateLimiter.RateLimitResult.permitted());
        when(accountAuthRateLimiter.checkLogin(any(), any())).thenReturn(AccountAuthRateLimiter.RateLimitResult.permitted());
    }
}
