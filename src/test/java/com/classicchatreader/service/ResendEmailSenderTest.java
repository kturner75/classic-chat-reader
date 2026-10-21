package com.classicchatreader.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Against a local stand-in for api.resend.com: what we post, and that failures never throw. */
class ResendEmailSenderTest {

    private HttpServer server;
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"id\":\"email-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ResendEmailSender sender(String apiKey, String from) {
        return new ResendEmailSender(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), apiKey, from);
    }

    @Test
    void postsTheMessageWithTheApiKey() {
        ResendEmailSender sender = sender("re_test_key", "Classic Chat Reader <no-reply@classicchatreader.com>");

        assertTrue(sender.isConfigured());
        assertTrue(sender.send("student@columbiastate.edu", "Reset", "plain body", "<p>html body</p>"));
        assertEquals("Bearer re_test_key", authHeader.get());
        assertTrue(body.get().contains("\"to\":[\"student@columbiastate.edu\"]"), body.get());
        assertTrue(body.get().contains("\"from\":\"Classic Chat Reader <no-reply@classicchatreader.com>\""), body.get());
        assertTrue(body.get().contains("\"text\":\"plain body\""), body.get());
    }

    @Test
    void providerErrorReturnsFalse() {
        status.set(422);
        assertFalse(sender("re_test_key", "no-reply@classicchatreader.com").send("a@b.co", "s", "t", "h"));
    }

    @Test
    void unconfiguredSenderSendsNothing() {
        ResendEmailSender sender = sender("", "no-reply@classicchatreader.com");

        assertFalse(sender.isConfigured());
        assertFalse(sender.send("a@b.co", "s", "t", "h"));
        assertNull(body.get());
    }
}
