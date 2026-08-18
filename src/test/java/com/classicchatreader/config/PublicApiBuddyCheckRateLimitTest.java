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
 * Verifies buddy-check uses a separate rate-limit key/bucket from CHAT.
 */
@ExtendWith(MockitoExtension.class)
class PublicApiBuddyCheckRateLimitTest {

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
                "local",
                "",
                60,
                30,
                45,
                30,
                0,
                0,
                0,
                null
        );
        responseBody = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    @Test
    void checkComment_usesBuddyCheckBucket_notChat() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/check-comment");
        when(request.getContextPath()).thenReturn("");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(keyCaptor.capture(), eq(30), any(Duration.class));
        assertTrue(keyCaptor.getValue().startsWith("BUDDY_CHECK:"), keyCaptor.getValue());
        assertFalse(keyCaptor.getValue().startsWith("CHAT:"));
    }

    @Test
    void chat_usesChatBucket_notBuddyCheck() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/chat");
        when(request.getContextPath()).thenReturn("");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(keyCaptor.capture(), eq(45), any(Duration.class));
        assertTrue(keyCaptor.getValue().startsWith("CHAT:"), keyCaptor.getValue());
        assertFalse(keyCaptor.getValue().startsWith("BUDDY_CHECK:"));
    }

    @Test
    void buddyCheckAndChat_consumeIndependentKeys() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getContextPath()).thenReturn("");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

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

    @Test
    void buddyCheck_rateLimited_returns429WithoutConsumingChat() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/reading-buddy/check-comment");
        when(request.getContextPath()).thenReturn("");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(429);
        verify(rateLimiter, never()).tryConsume(org.mockito.ArgumentMatchers.startsWith("CHAT:"), anyInt(), any());
    }
}
