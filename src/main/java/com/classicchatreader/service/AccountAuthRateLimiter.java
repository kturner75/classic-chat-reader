package com.classicchatreader.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AccountAuthRateLimiter {

    private static final int DEFAULT_WINDOW_SECONDS = 60;
    private final int windowSeconds;
    private final int registerIpMaxRequests;
    private final int registerEmailMaxRequests;
    private final int loginIpMaxRequests;
    private final int loginEmailMaxRequests;
    private final int maxKeys;

    private final AtomicInteger cleanupTicker = new AtomicInteger();
    private final ConcurrentHashMap<String, CounterWindow> windows = new ConcurrentHashMap<>();

    public AccountAuthRateLimiter(
            @Value("${account.auth.rate-limit.window-seconds:60}") int windowSeconds,
            @Value("${account.auth.rate-limit.register.ip-max-requests:12}") int registerIpMaxRequests,
            @Value("${account.auth.rate-limit.register.email-max-requests:6}") int registerEmailMaxRequests,
            @Value("${account.auth.rate-limit.login.ip-max-requests:40}") int loginIpMaxRequests,
            @Value("${account.auth.rate-limit.login.email-max-requests:12}") int loginEmailMaxRequests,
            @Value("${account.auth.rate-limit.max-keys:20000}") int maxKeys) {
        this.windowSeconds = Math.max(1, windowSeconds);
        this.registerIpMaxRequests = Math.max(1, registerIpMaxRequests);
        this.registerEmailMaxRequests = Math.max(1, registerEmailMaxRequests);
        this.loginIpMaxRequests = Math.max(1, loginIpMaxRequests);
        this.loginEmailMaxRequests = Math.max(1, loginEmailMaxRequests);
        this.maxKeys = Math.max(1000, maxKeys);
    }

    public RateLimitResult checkRegister(HttpServletRequest request, String email) {
        return check("register", request, email, registerIpMaxRequests, registerEmailMaxRequests);
    }

    public RateLimitResult checkLogin(HttpServletRequest request, String email) {
        return check("login", request, email, loginIpMaxRequests, loginEmailMaxRequests);
    }

    private RateLimitResult check(
            String action,
            HttpServletRequest request,
            String email,
            int ipLimit,
            int emailLimit) {
        Duration window = Duration.ofSeconds(windowSeconds);

        String clientKey = action + ":ip:" + hash(resolveClientIp(request));
        if (!tryConsume(clientKey, ipLimit, window)) {
            return RateLimitResult.limited(windowSeconds, "ip");
        }

        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail != null) {
            String emailKey = action + ":email:" + hash(normalizedEmail);
            if (!tryConsume(emailKey, emailLimit, window)) {
                return RateLimitResult.limited(windowSeconds, "email");
            }
        }

        return RateLimitResult.permitted();
    }

    private boolean tryConsume(String key, int maxRequests, Duration window) {
        if (key == null || key.isBlank() || maxRequests <= 0 || window == null || window.isZero() || window.isNegative()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        CounterWindow counter = windows.computeIfAbsent(key, ignored -> new CounterWindow(now));
        boolean allowed = counter.tryConsume(now, windowMillis, maxRequests);

        int tick = cleanupTicker.incrementAndGet();
        if ((tick & 0xFF) == 0 || windows.size() > maxKeys) {
            cleanup(now, window.multipliedBy(3).toMillis());
        }

        return allowed;
    }

    private void cleanup(long now, long staleThresholdMillis) {
        for (Map.Entry<String, CounterWindow> entry : windows.entrySet()) {
            if (now - entry.getValue().getLastSeenMillis() > staleThresholdMillis) {
                windows.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0].trim();
            }
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr == null || remoteAddr.isBlank()) ? "unknown" : remoteAddr.trim();
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private String hash(String value) {
        if (value == null || value.isBlank()) {
            return "na";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return encoded.substring(0, Math.min(22, encoded.length()));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public record RateLimitResult(
            boolean allowed,
            Integer retryAfterSeconds,
            String scope
    ) {
        public static RateLimitResult permitted() {
            return new RateLimitResult(true, null, null);
        }

        public static RateLimitResult limited(int retryAfterSeconds, String scope) {
            int retry = retryAfterSeconds > 0 ? retryAfterSeconds : DEFAULT_WINDOW_SECONDS;
            return new RateLimitResult(false, retry, scope);
        }
    }

    private static final class CounterWindow {
        private long windowStartMillis;
        private int count;
        private long lastSeenMillis;

        private CounterWindow(long now) {
            this.windowStartMillis = now;
            this.lastSeenMillis = now;
        }

        private synchronized boolean tryConsume(long now, long windowMillis, int limit) {
            if (now - windowStartMillis >= windowMillis) {
                windowStartMillis = now;
                count = 0;
            }

            lastSeenMillis = now;
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }

        private synchronized long getLastSeenMillis() {
            return lastSeenMillis;
        }
    }
}
