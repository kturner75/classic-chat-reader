package com.classicchatreader.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class ReaderProfileService {

    private final String cookieName;
    private final boolean secureCookie;
    private static final Duration READER_COOKIE_TTL = Duration.ofDays(365L * 5L);

    public ReaderProfileService(
            @Value("${reader.profile.cookie-name:pdr_reader_profile}") String cookieName,
            @Value("${security.public.session.secure-cookie:false}") boolean secureCookie) {
        this.cookieName = (cookieName == null || cookieName.isBlank()) ? "pdr_reader_profile" : cookieName;
        this.secureCookie = secureCookie;
    }

    public String resolveReaderId(HttpServletRequest request, HttpServletResponse response) {
        String existing = readCookie(request);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }

        String generated = UUID.randomUUID().toString();
        ResponseCookie cookie = ResponseCookie.from(cookieName, generated)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(READER_COOKIE_TTL)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return generated;
    }

    String readCookie(HttpServletRequest request) {
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
}
