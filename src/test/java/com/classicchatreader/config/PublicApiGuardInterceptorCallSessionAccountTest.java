package com.classicchatreader.config;

import com.classicchatreader.service.AccountAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BL-081: registered reader accounts may mint Call Character sessions
 * without a collaborator cookie or API key. Pregen stays operator-only.
 */
@ExtendWith(MockitoExtension.class)
class PublicApiGuardInterceptorCallSessionAccountTest {

    @Mock
    private PublicApiRateLimiter rateLimiter;
    @Mock
    private AccountAuthService accountAuthService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private PublicApiGuardInterceptor interceptor;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new PublicApiGuardInterceptor(
                rateLimiter,
                null,
                "public",
                "test-api-key",
                60,
                2,
                45,
                30,
                2,
                0,
                0,
                accountAuthService
        );
        responseBody = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        lenient().when(request.getContextPath()).thenReturn("");
        lenient().when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        lenient().when(request.getMethod()).thenReturn("POST");
    }

    @Test
    void callSession_withoutAuth_returns401() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/characters/char-1/call-session");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(accountAuthService.resolveAuthenticatedPrincipal(request)).thenReturn(Optional.empty());

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(responseBody.toString().contains("Authentication required"));
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void callSession_withAccountPrincipal_isAllowedAndRateLimitedAsChat() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/characters/char-1/call-session");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(accountAuthService.resolveAuthenticatedPrincipal(request))
                .thenReturn(Optional.of(new AccountAuthService.AccountPrincipal("reader-1", "k@example.com")));

        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(keyCaptor.capture(), anyInt(), any(Duration.class));
        assertTrue(keyCaptor.getValue().startsWith("CHAT:account:reader-1"), keyCaptor.getValue());
    }

    @Test
    void characterChat_withAccountPrincipal_isAllowed() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/characters/char-1/chat");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(accountAuthService.resolveAuthenticatedPrincipal(request))
                .thenReturn(Optional.of(new AccountAuthService.AccountPrincipal("reader-1", "k@example.com")));

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(rateLimiter).tryConsume(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void pregen_withAccountPrincipalOnly_stillUnauthorized() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/pregen/book/book-1");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(accountAuthService, never()).resolveAuthenticatedPrincipal(any());
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any(Duration.class));
    }
}
