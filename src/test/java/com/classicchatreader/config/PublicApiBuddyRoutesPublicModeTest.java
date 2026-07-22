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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Public-mode auth + rate-limit verification unique to reading-buddy routes.
 * <p>
 * Bucket-prefix details in local mode live in {@link PublicApiBuddyCheckRateLimitTest};
 * this class focuses on public-mode 401 and true CHAT vs BUDDY_CHECK independence
 * after a buddy-check rate-limit deny.
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
    void preferencesUpdate_withoutAuth_returns401() throws Exception {
        when(request.getMethod()).thenReturn("PUT");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/preferences");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(responseBody.toString().contains("Authentication required"));
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void historyDelete_withoutAuth_returns401() throws Exception {
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/history");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(responseBody.toString().contains("Authentication required"));
        verify(rateLimiter, never()).tryConsume(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void authenticated_chatAndCheckComment_useDistinctBuckets() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-API-Key")).thenReturn("test-api-key");

        when(request.getRequestURI()).thenReturn("/api/reading-buddy/check-comment");
        assertTrue(interceptor.preHandle(request, response, new Object()));

        when(request.getRequestURI()).thenReturn("/api/reading-buddy/chat");
        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryConsume(keyCaptor.capture(), anyInt(), any(Duration.class));
        assertTrue(keyCaptor.getAllValues().get(0).startsWith("BUDDY_CHECK:"), keyCaptor.getAllValues().get(0));
        assertTrue(keyCaptor.getAllValues().get(1).startsWith("CHAT:"), keyCaptor.getAllValues().get(1));
    }

    @Test
    void checkComment_rateLimited_chatBucketStillUsable() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-API-Key")).thenReturn("test-api-key");

        // Deny only BUDDY_CHECK keys; CHAT remains consumable (true independence).
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key == null || !key.startsWith("BUDDY_CHECK:");
        });

        when(request.getRequestURI()).thenReturn("/api/reading-buddy/check-comment");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(429);
        assertTrue(responseBody.toString().contains("Rate limit exceeded"));

        responseBody.getBuffer().setLength(0);

        when(request.getRequestURI()).thenReturn("/api/reading-buddy/chat");
        assertTrue(interceptor.preHandle(request, response, new Object()),
                "chat must still be allowed after buddy-check rate limit");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryConsume(keyCaptor.capture(), anyInt(), any(Duration.class));
        assertTrue(keyCaptor.getAllValues().get(0).startsWith("BUDDY_CHECK:"));
        assertTrue(keyCaptor.getAllValues().get(1).startsWith("CHAT:"));
    }
}
