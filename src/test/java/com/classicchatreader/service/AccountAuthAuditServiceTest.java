package com.classicchatreader.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAuthAuditServiceTest {

    private final AccountAuthAuditService service = new AccountAuthAuditService();

    @Test
    void buildEvent_hashesEmailAndIpAndKeepsStructuredFields() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        request.setAttribute("requestId", "req-123");

        Map<String, Object> event = service.buildEvent(
                "login",
                "invalid_credentials",
                request,
                "Reader@Example.com",
                "user-1",
                30,
                "ip"
        );

        assertEquals("login", event.get("action"));
        assertEquals("invalid_credentials", event.get("outcome"));
        assertEquals("req-123", event.get("requestId"));
        assertEquals("user-1", event.get("userId"));
        assertEquals(30, event.get("retryAfterSeconds"));
        assertEquals("ip", event.get("reason"));

        String emailHash = String.valueOf(event.get("emailHash"));
        String ipHash = String.valueOf(event.get("ipHash"));
        assertFalse(emailHash.contains("reader@example.com"));
        assertFalse(ipHash.contains("203.0.113.5"));
        assertNotEquals("Reader@Example.com", emailHash);
        assertNotEquals("203.0.113.5", ipHash);
        assertTrue(emailHash.length() >= 10);
        assertTrue(ipHash.length() >= 10);
    }
}
