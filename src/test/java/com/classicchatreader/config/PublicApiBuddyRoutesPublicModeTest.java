package com.classicchatreader.config;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Public-mode auth + rate-limit verification for reading-buddy sensitive routes.
 * <p>
 * Prod rollout expects: unauthenticated chat/check-comment → 401; authenticated
 * traffic rate-limited on separate CHAT vs BUDDY_CHECK buckets.
 */
@ExtendWith(MockitoExtension.class)
class PublicApiBuddyRoutesPublicModeTest {

    @Mock
    private PublicApiRateLimiter rateLimiter;
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
                30,
                2,
                2,
                0,
                0,
                0
        );
        responseBody = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        lenient().when(request.getContextPath()).thenReturn("");
        lenient().when(request.getRemoteAddr()).thenReturn("203.0.113.10");
    }

    @Test
    void chat_withoutAuth_returns401() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/chat");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(responseBody.toString().contains("Authentication required"));
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void checkComment_withoutAuth_returns401() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/check-comment");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(responseBody.toString().contains("Authentication required"));
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void chat_withApiKey_usesChatBucket() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/chat");
        when(request.getHeader("X-API-Key")).thenReturn("test-api-key");

        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(keyCaptor.capture(), eq(2), any(Duration.class));
        assertTrue(keyCaptor.getValue().startsWith("CHAT:"), keyCaptor.getValue());
        assertFalse(keyCaptor.getValue().startsWith("BUDDY_CHECK:"));
    }

    @Test
    void checkComment_withApiKey_usesBuddyCheckBucket() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/check-comment");
        when(request.getHeader("X-API-Key")).thenReturn("test-api-key");

        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(keyCaptor.capture(), eq(2), any(Duration.class));
        assertTrue(keyCaptor.getValue().startsWith("BUDDY_CHECK:"), keyCaptor.getValue());
        assertFalse(keyCaptor.getValue().startsWith("CHAT:"));
    }

    @Test
    void checkComment_rateLimited_returns429_independentOfChat() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/check-comment");
        when(request.getHeader("X-API-Key")).thenReturn("test-api-key");
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(429);
        assertTrue(responseBody.toString().contains("Rate limit exceeded"));
        verify(rateLimiter, never()).tryConsume(org.mockito.ArgumentMatchers.startsWith("CHAT:"), anyInt(), any());
    }

    @Test
    void buddyCheckAndChat_authenticated_consumeIndependentKeys() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-API-Key")).thenReturn("test-api-key");

        when(request.getRequestURI()).thenReturn("/api/reading-buddy/check-comment");
        assertTrue(interceptor.preHandle(request, response, new Object()));

        when(request.getRequestURI()).thenReturn("/api/reading-buddy/chat");
        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryConsume(keyCaptor.capture(), anyInt(), any(Duration.class));
        assertEquals(2, keyCaptor.getAllValues().size());
        assertTrue(keyCaptor.getAllValues().get(0).startsWith("BUDDY_CHECK:"));
        assertTrue(keyCaptor.getAllValues().get(1).startsWith("CHAT:"));
    }
}
