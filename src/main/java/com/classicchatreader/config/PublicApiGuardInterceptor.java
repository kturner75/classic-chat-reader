package com.classicchatreader.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.classicchatreader.service.PublicSessionAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

@Component
public class PublicApiGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PublicApiGuardInterceptor.class);

    private final PublicApiRateLimiter rateLimiter;
    private final PublicSessionAuthService sessionAuthService;
    private final String deploymentMode;
    private final String publicApiKey;
    private final int generationLimit;
    private final int chatLimit;
    private final int authenticatedGenerationLimit;
    private final int authenticatedChatLimit;
    private final int windowSeconds;

    public PublicApiGuardInterceptor(
            @Nullable PublicApiRateLimiter rateLimiter,
            @Nullable PublicSessionAuthService sessionAuthService,
            @Value("${deployment.mode:local}") String deploymentMode,
            @Value("${security.public.api-key:}") String publicApiKey,
            @Value("${security.public.rate-limit.window-seconds:60}") int windowSeconds,
            @Value("${security.public.rate-limit.generation-requests:30}") int generationLimit,
            @Value("${security.public.rate-limit.chat-requests:45}") int chatLimit,
            @Value("${security.public.rate-limit.authenticated-generation-requests:0}")
            int authenticatedGenerationLimit,
            @Value("${security.public.rate-limit.authenticated-chat-requests:0}")
            int authenticatedChatLimit) {
        this.rateLimiter = rateLimiter != null ? rateLimiter : new InMemoryIpRateLimiter(20000);
        this.sessionAuthService = sessionAuthService;
        this.deploymentMode = deploymentMode;
        this.publicApiKey = publicApiKey;
        this.generationLimit = Math.max(1, generationLimit);
        this.chatLimit = Math.max(1, chatLimit);
        this.authenticatedGenerationLimit = authenticatedGenerationLimit > 0
                ? authenticatedGenerationLimit
                : this.generationLimit;
        this.authenticatedChatLimit = authenticatedChatLimit > 0
                ? authenticatedChatLimit
                : this.chatLimit;
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = stripContextPath(request);
        String method = request.getMethod();
        SensitiveApiRequestMatcher.EndpointType endpointType = SensitiveApiRequestMatcher.classify(method, path);
        if (endpointType == SensitiveApiRequestMatcher.EndpointType.NONE) {
            return true;
        }

        String principalScope = null;
        if (isPublicMode()) {
            boolean apiKeyConfigured = publicApiKey != null && !publicApiKey.isBlank();
            boolean sessionAuthConfigured = sessionAuthService != null && sessionAuthService.isPasswordConfigured();

            if (endpointType == SensitiveApiRequestMatcher.EndpointType.ADMIN) {
                if (!apiKeyConfigured) {
                    log.warn("Public mode is enabled but no admin API key is configured; blocking admin endpoint {}", path);
                    writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                            "{\"error\":\"Public mode admin security is not configured\"}");
                    return false;
                }

                String providedApiKey = request.getHeader("X-API-Key");
                boolean apiKeyAuthenticated = constantTimeEquals(publicApiKey, providedApiKey);
                if (!apiKeyAuthenticated) {
                    writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "{\"error\":\"Admin API key required\"}");
                    return false;
                }
                principalScope = "api:" + shortHash(providedApiKey);
            } else {
                if (!apiKeyConfigured && !sessionAuthConfigured) {
                    log.warn("Public mode is enabled but no sensitive endpoint auth is configured; blocking sensitive endpoint {}", path);
                    writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                            "{\"error\":\"Public mode security is not configured\"}");
                    return false;
                }

                boolean apiKeyAuthenticated = false;
                if (apiKeyConfigured) {
                    String providedApiKey = request.getHeader("X-API-Key");
                    apiKeyAuthenticated = constantTimeEquals(publicApiKey, providedApiKey);
                    if (apiKeyAuthenticated) {
                        principalScope = "api:" + shortHash(providedApiKey);
                    }
                }

                String sessionPrincipal = sessionAuthService != null
                        ? sessionAuthService.resolveAuthenticatedPrincipal(request)
                        : null;
                boolean sessionAuthenticated = sessionPrincipal != null;
                if (!apiKeyAuthenticated && sessionAuthenticated) {
                    principalScope = sessionPrincipal;
                }
                if (!apiKeyAuthenticated && !sessionAuthenticated) {
                    writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "{\"error\":\"Authentication required\"}");
                    return false;
                }
            }
        }

        String ip = resolveClientIp(request);
        boolean authenticatedScope = principalScope != null && !principalScope.isBlank();
        int limit = resolveLimit(endpointType, authenticatedScope);
        String limiterKey = endpointType.name() + ":" + (authenticatedScope ? principalScope : "ip:" + ip);

        boolean allowed = rateLimiter.tryConsume(limiterKey, limit, Duration.ofSeconds(windowSeconds));
        if (!allowed) {
            response.setHeader("Retry-After", String.valueOf(windowSeconds));
            writeJson(response, 429,
                    "{\"error\":\"Rate limit exceeded\",\"limit\":" + limit + ",\"windowSeconds\":" + windowSeconds + "}");
            return false;
        }

        return true;
    }

    private boolean isPublicMode() {
        return "public".equalsIgnoreCase(deploymentMode);
    }

    private String stripContextPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIndex = xForwardedFor.indexOf(',');
            String candidate = commaIndex >= 0 ? xForwardedFor.substring(0, commaIndex) : xForwardedFor;
            if (!candidate.isBlank()) {
                return candidate.trim();
            }
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }

        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }

    private boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private void writeJson(HttpServletResponse response, int statusCode, String payload) throws Exception {
        response.setStatus(statusCode);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(payload);
    }

    private int resolveLimit(SensitiveApiRequestMatcher.EndpointType endpointType, boolean authenticatedScope) {
        if (endpointType == SensitiveApiRequestMatcher.EndpointType.CHAT) {
            return authenticatedScope ? authenticatedChatLimit : chatLimit;
        }
        if (endpointType == SensitiveApiRequestMatcher.EndpointType.ADMIN) {
            return authenticatedGenerationLimit;
        }
        return authenticatedScope ? authenticatedGenerationLimit : generationLimit;
    }

    private String shortHash(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 22);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
