package com.classicchatreader.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PublicSessionAuthService {

    private final String collaboratorPassword;
    private final String cookieName;
    private final int sessionTtlMinutes;
    private final boolean secureCookie;
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupTicker = new AtomicInteger();
    private final SecureRandom secureRandom = new SecureRandom();

    public PublicSessionAuthService(
            @Value("${security.public.collaborator.password:}") String collaboratorPassword,
            @Value("${security.public.session.cookie-name:pdr_collab_session}") String cookieName,
            @Value("${security.public.session.ttl-minutes:480}") int sessionTtlMinutes,
            @Value("${security.public.session.secure-cookie:false}") boolean secureCookie) {
        this.collaboratorPassword = collaboratorPassword == null ? "" : collaboratorPassword;
        this.cookieName = (cookieName == null || cookieName.isBlank()) ? "pdr_collab_session" : cookieName;
        this.sessionTtlMinutes = Math.max(15, sessionTtlMinutes);
        this.secureCookie = secureCookie;
    }

    public boolean isPasswordConfigured() {
        return !collaboratorPassword.isBlank();
    }

    public boolean authenticatePassword(String providedPassword) {
        if (!isPasswordConfigured() || providedPassword == null) {
            return false;
        }
        return MessageDigest.isEqual(
                collaboratorPassword.getBytes(StandardCharsets.UTF_8),
                providedPassword.getBytes(StandardCharsets.UTF_8)
        );
    }

    public void createSession(HttpServletResponse response) {
        String token = newToken();
        long expiresAt = System.currentTimeMillis() + Duration.ofMinutes(sessionTtlMinutes).toMillis();
        sessions.put(token, expiresAt);
        writeSessionCookie(response, token, sessionTtlMinutes * 60);
        cleanupIfNeeded();
    }

    public void clearSession(HttpServletRequest request, HttpServletResponse response) {
        String token = readToken(request);
        if (token != null) {
            sessions.remove(token);
        }
        writeSessionCookie(response, "", 0);
    }

    public boolean isAuthenticated(HttpServletRequest request) {
        return resolveAuthenticatedPrincipal(request) != null;
    }

    public String resolveAuthenticatedPrincipal(HttpServletRequest request) {
        String token = readToken(request);
        if (token == null || token.isBlank()) {
            return null;
        }

        Long expiresAt = sessions.get(token);
        long now = System.currentTimeMillis();
        if (expiresAt == null) {
            return null;
        }
        if (expiresAt < now) {
            sessions.remove(token);
            return null;
        }

        sessions.put(token, now + Duration.ofMinutes(sessionTtlMinutes).toMillis());
        cleanupIfNeeded();
        return "session:" + shortHash(token);
    }

    public String getCookieName() {
        return cookieName;
    }

    private void writeSessionCookie(HttpServletResponse response, String value, int maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String newToken() {
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 22);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private void cleanupIfNeeded() {
        int tick = cleanupTicker.incrementAndGet();
        if ((tick & 0xFF) != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : sessions.entrySet()) {
            if (entry.getValue() < now) {
                sessions.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
