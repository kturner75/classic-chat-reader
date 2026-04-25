package com.classicchatreader.config;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestCorrelation {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = "requestId";
    public static final String UNKNOWN = "unknown";

    private RequestCorrelation() {
    }

    public static String resolveRequestId(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        Object requestId = request.getAttribute(ATTRIBUTE_NAME);
        if (requestId instanceof String value && !value.isBlank()) {
            return value;
        }
        return UNKNOWN;
    }
}
