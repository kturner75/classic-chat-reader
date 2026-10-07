package com.classicchatreader.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guard must classify the path Spring routes on. Each spelling below reaches the same handler as
 * /api/import/gutenberg/13707/contents, so each must be refused without the admin key.
 */
@ExtendWith(MockitoExtension.class)
class PublicApiGuardInterceptorPathVariantsTest {

    @Mock
    private PublicApiRateLimiter rateLimiter;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private PublicApiGuardInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new PublicApiGuardInterceptor(rateLimiter, null, "public", "test-api-key", 60, 2, 45, 30, 2, 0, 0, null);
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        lenient().when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        lenient().when(request.getContextPath()).thenReturn("");
        lenient().when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        lenient().when(request.getMethod()).thenReturn("GET");
        lenient().when(request.getHeader("X-API-Key")).thenReturn(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/import/gutenberg/13707/contents",
        "/api/import/gutenberg/13707;x=1/contents",
        "/api/import/gutenberg/%31%33707/contents",
        "/api/%69mport/gutenberg/13707/contents",
        "/api/import/gutenberg/+13707/contents",
        "/api/import/gutenberg/0x35DB/contents",
        "/api/studio;x/roster/gutenberg/17396",
    })
    void everySpellingOfAnAdminRouteNeedsTheAdminKey(String rawUri) throws Exception {
        when(request.getRequestURI()).thenReturn(rawUri);

        assertFalse(interceptor.preHandle(request, response, new Object()), rawUri);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
