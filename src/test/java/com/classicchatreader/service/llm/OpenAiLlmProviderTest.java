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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
