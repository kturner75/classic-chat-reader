package com.classicchatreader.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Set;

/** Operator adapters for the local Studio accept only direct loopback requests (no proxy hop). */
final class LocalStudioAccess {

    private LocalStudioAccess() {
    }

    static void require(HttpServletRequest request) {
        if (!Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr()) ||
                request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-For") != null
                || request.getHeader("X-Real-IP") != null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Studio access required");
        try {
            String host = new URI(request.getRequestURL().toString()).getHost();
            if (!Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(host)) throw new IllegalArgumentException();
            String origin = request.getHeader("Origin");
            if (origin != null && !Set.of("http://localhost:5173", "http://127.0.0.1:5173").contains(origin)) throw new IllegalArgumentException();
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Studio access required"); }
    }
}
