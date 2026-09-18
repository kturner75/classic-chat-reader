package com.classicchatreader.service;

import jakarta.servlet.http.HttpServletRequest;
import com.classicchatreader.config.RequestCorrelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

        String ipHash = hash(RequestPrivacy.resolveClientIp(request));
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

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private String hash(String value) {
        return RequestPrivacy.hash(value);
    }

    private String nullSafe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
