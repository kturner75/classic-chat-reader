package com.classicchatreader.service;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Shared client-identifier hashing for audit rows and logs. No raw IPs or user agents are stored. */
public final class RequestPrivacy {

    private RequestPrivacy() {
    }

    public static String resolveClientIp(HttpServletRequest request) {
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

    /** Truncated SHA-256; stable within a deployment, not reversible to the original value. */
    public static String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return encoded.substring(0, Math.min(22, encoded.length()));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
