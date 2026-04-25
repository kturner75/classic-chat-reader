package com.classicchatreader.service;

import jakarta.servlet.http.HttpServletRequest;
import com.classicchatreader.config.RequestCorrelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class AccountAuthAuditService {

    private static final Logger log = LoggerFactory.getLogger(AccountAuthAuditService.class);

    public void record(
            String action,
            String outcome,
            HttpServletRequest request,
            String email,
            String userId,
            Integer retryAfterSeconds,
            String reason) {
        Map<String, Object> event = buildEvent(action, outcome, request, email, userId, retryAfterSeconds, reason);
        log.info("account_auth_audit {}", event);
    }

    Map<String, Object> buildEvent(
            String action,
            String outcome,
            HttpServletRequest request,
            String email,
            String userId,
            Integer retryAfterSeconds,
            String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", nullSafe(action, "unknown"));
        event.put("outcome", nullSafe(outcome, "unknown"));

        String requestId = RequestCorrelation.resolveRequestId(request);
        if (requestId != null && !requestId.isBlank()) {
            event.put("requestId", requestId);
        }

        String emailHash = hash(normalize(email));
        if (emailHash != null) {
            event.put("emailHash", emailHash);
        }

        String ipHash = hash(resolveClientIp(request));
        if (ipHash != null) {
            event.put("ipHash", ipHash);
        }

        if (userId != null && !userId.isBlank()) {
            event.put("userId", userId);
        }
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            event.put("retryAfterSeconds", retryAfterSeconds);
        }
        if (reason != null && !reason.isBlank()) {
            event.put("reason", reason);
        }
        return event;
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
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
        return (remoteAddr == null || remoteAddr.isBlank()) ? null : remoteAddr.trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
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

    private String nullSafe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
