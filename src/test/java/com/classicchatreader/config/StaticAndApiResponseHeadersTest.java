package com.classicchatreader.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Headers from the real embedded server: gzip (nginx only gzips HTML) and static-file revalidation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Its own DB: the smoke profile's shared smokedb is create-drop (see CharacterChatPersistenceEndToEndTest).
        "spring.datasource.url=jdbc:h2:mem:response-headers;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("smoke")
class StaticAndApiResponseHeadersTest {

    @Value("${local.server.port}")
    private int port;

    private HttpResponse<byte[]> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String contentEncoding(String path) throws Exception {
        HttpResponse<byte[]> response = get(path, Map.of("Accept-Encoding", "gzip"));
        assertEquals(200, response.statusCode(), path);
        return response.headers().firstValue("Content-Encoding").orElse("none");
    }

    @Test
    void gzipsScriptsStylesAndJson() throws Exception {
        assertEquals("gzip", contentEncoding("/js/reader.js"));
        assertEquals("gzip", contentEncoding("/css/reader.css"));
        assertEquals("gzip", contentEncoding("/api/import/popular"));
    }

    @Test
    void staticFilesAskBrowsersToRevalidateAndAnswerUnchangedWith304() throws Exception {
        // "/" is what people load; Spring forwards it to index.html, and the header must survive that.
        for (String path : List.of("/", "/js/reader.js", "/css/reader.css", "/index.html")) {
            HttpResponse<byte[]> first = get(path, Map.of());
            assertEquals(200, first.statusCode(), path);
            assertEquals("no-cache", first.headers().firstValue("Cache-Control").orElse("none"), path);
            String lastModified = first.headers().firstValue("Last-Modified").orElseThrow();

            HttpResponse<byte[]> again = get(path, Map.of("If-Modified-Since", lastModified));
            assertEquals(304, again.statusCode(), path + " unchanged since the last visit");
            assertEquals(0, again.body().length, path);
        }
    }
}
