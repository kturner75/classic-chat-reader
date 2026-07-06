package com.classicchatreader.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiLlmProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsGpt55ChatCompletionRequest() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        startServer(exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedRequest.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] body = """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "A polished answer."
                          }
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        });

        OpenAiLlmProvider provider = new OpenAiLlmProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key",
                "gpt-5.5",
                5
        );

        String generated = provider.generate("Help me read this chapter.", LlmOptions.full(0.2, 0.9, 700));

        assertEquals("A polished answer.", generated);
        assertEquals("Bearer test-key", capturedAuth.get());
        assertEquals("gpt-5.5", capturedRequest.get().get("model").asText());
        assertEquals("Help me read this chapter.", capturedRequest.get().get("messages").get(0).get("content").asText());
        assertEquals(700, capturedRequest.get().get("max_completion_tokens").asInt());
    }

    @Test
    void retriesWithoutTemperatureWhenModelRejectsIt() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        AtomicReference<JsonNode> secondRequest = new AtomicReference<>();
        startServer(exchange -> {
            int call = callCount.incrementAndGet();
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            if (call == 1) {
                byte[] body = """
                        {
                          "error": {
                            "message": "Unsupported value: 'temperature' does not support 0.8 with this model. Only the default (1) value is supported.",
                            "type": "invalid_request_error",
                            "param": "temperature",
                            "code": "unsupported_value"
                          }
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            secondRequest.set(request);
            byte[] body = """
                    {"choices":[{"message":{"content":"Recovered answer."}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        });

        OpenAiLlmProvider provider = new OpenAiLlmProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key",
                "gpt-5.5",
                5
        );

        String generated = provider.generate("Help me read this chapter.", LlmOptions.withTemperatureAndTopP(0.8, 0.9));

        assertEquals("Recovered answer.", generated);
        assertEquals(2, callCount.get());
        assertFalse(secondRequest.get().has("temperature"), "retry should omit temperature");
        assertFalse(secondRequest.get().has("top_p"), "retry should omit top_p");
    }

    @Test
    void doesNotRetryOnUnrelatedBadRequest() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        startServer(exchange -> {
            callCount.incrementAndGet();
            byte[] body = """
                    {"error":{"message":"Invalid request","type":"invalid_request_error","code":"invalid_request"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
        });

        OpenAiLlmProvider provider = new OpenAiLlmProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key",
                "gpt-5.5",
                5
        );

        assertThrows(LlmProviderException.class,
                () -> provider.generate("Help me read this chapter.", LlmOptions.withTemperatureAndTopP(0.8, 0.9)));
        assertEquals(1, callCount.get());
    }

    @Test
    void doesNotRetryForeverIfSecondAttemptAlsoFails() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        startServer(exchange -> {
            callCount.incrementAndGet();
            byte[] body = """
                    {"error":{"message":"still rejected","type":"invalid_request_error","param":"temperature","code":"unsupported_value"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
        });

        OpenAiLlmProvider provider = new OpenAiLlmProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key",
                "gpt-5.5",
                5
        );

        assertThrows(LlmProviderException.class,
                () -> provider.generate("Help me read this chapter.", LlmOptions.withTemperatureAndTopP(0.8, 0.9)));
        assertEquals(2, callCount.get(), "should retry exactly once, not loop");
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
