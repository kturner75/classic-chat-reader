package com.classicchatreader.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The real embedded server gzips the big landing-page responses (nginx only gzips HTML). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Its own DB: the smoke profile's shared smokedb is create-drop (see CharacterChatPersistenceEndToEndTest).
        "spring.datasource.url=jdbc:h2:mem:response-compression;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("smoke")
class ResponseCompressionTest {

    @Value("${local.server.port}")
    private int port;

    private String contentEncoding(String path) throws Exception {
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept-Encoding", "gzip")
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), path);
        return response.headers().firstValue("Content-Encoding").orElse("none");
    }

    @Test
    void gzipsScriptsStylesAndJson() throws Exception {
        assertEquals("gzip", contentEncoding("/js/reader.js"));
        assertEquals("gzip", contentEncoding("/css/reader.css"));
        assertEquals("gzip", contentEncoding("/api/import/popular"));
    }
}
