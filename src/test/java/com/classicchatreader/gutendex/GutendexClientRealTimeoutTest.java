package com.classicchatreader.gutendex;

import com.classicchatreader.gutendex.GutendexClient.LookupStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A server that accepts the request and then stalls must not hang the preview. */
class GutendexClientRealTimeoutTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startStalledServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2_000); // far longer than the client's read timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aStalledGutendexGivesUpAtTheReadTimeoutAndReportsUnavailable() {
        RestClient stalledLookup = GutendexClient.shortTimeoutClient(baseUrl, Duration.ofMillis(500), Duration.ofMillis(400));
        GutendexClient client = new GutendexClient(RestClient.builder().baseUrl(baseUrl).build(), stalledLookup);

        long start = System.nanoTime();
        GutendexClient.BookLookupResult result = client.lookupBook(13707);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(LookupStatus.UNAVAILABLE, result.status());
        assertTrue(elapsedMs < 1_500, "gave up in " + elapsedMs + " ms instead of waiting for the 2 s stall");
    }
}
