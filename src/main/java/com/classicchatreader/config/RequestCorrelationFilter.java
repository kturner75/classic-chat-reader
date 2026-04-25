package com.classicchatreader.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = normalizeHeader(request.getHeader(RequestCorrelation.HEADER_NAME));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        request.setAttribute(RequestCorrelation.ATTRIBUTE_NAME, requestId);
        response.setHeader(RequestCorrelation.HEADER_NAME, requestId);
        MDC.put(RequestCorrelation.ATTRIBUTE_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestCorrelation.ATTRIBUTE_NAME);
        }
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 80) {
            trimmed = trimmed.substring(0, 80);
        }
        return trimmed;
    }
}
