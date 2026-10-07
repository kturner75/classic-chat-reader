package com.classicchatreader.gutendex;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An HTTP error page is not the book. */
class GutendexClientFetchContentTest {

    private HttpServer server;
    private GutendexClient client;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> reply(ex, 200, "<html><body>the book</body></html>"));
        server.createContext("/gone", ex -> reply(ex, 404, "<html><body>Not Found</body></html>"));
        server.createContext("/down", ex -> reply(ex, 503, "<html><body>Service Unavailable</body></html>"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        RestClient rest = RestClient.builder().baseUrl(base).build();
        client = new GutendexClient(rest, rest);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void aSuccessfulResponseIsReturned() {
        assertEquals("<html><body>the book</body></html>", client.fetchContent(base + "/ok"));
    }

    @Test
    void anErrorStatusFailsInsteadOfReturningTheErrorPage() {
        RuntimeException notFound = assertThrows(RuntimeException.class, () -> client.fetchContent(base + "/gone"));
        assertTrue(notFound.getMessage().contains("404"), notFound.getMessage());
        RuntimeException down = assertThrows(RuntimeException.class, () -> client.fetchContent(base + "/down"));
        assertTrue(down.getMessage().contains("503"), down.getMessage());
    }
}
