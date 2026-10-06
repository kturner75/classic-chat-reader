package com.classicchatreader.gutendex;

import com.classicchatreader.gutendex.GutendexClient.LookupStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class GutendexClientLookupTest {

    private MockRestServiceServer server;
    private GutendexClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder lookupBuilder = RestClient.builder().baseUrl("https://gutendex.com");
        server = MockRestServiceServer.bindTo(lookupBuilder).build();
        // The regular client is not used by lookupBook; it only needs to exist.
        client = new GutendexClient(RestClient.builder().baseUrl("https://gutendex.com").build(), lookupBuilder.build());
    }

    @Test
    void lookupReturnsTheBookWhenGutendexAnswers() {
        server.expect(requestTo("https://gutendex.com/books/13707/")).andExpect(method(GET))
            .andRespond(withSuccess("""
                {"id":13707,"title":"Twice-told tales","authors":[{"name":"Hawthorne, Nathaniel"}],
                 "subjects":[],"bookshelves":[],"languages":["en"],
                 "formats":{"text/html":"https://www.gutenberg.org/ebooks/13707.html.images"},"download_count":10}
                """, MediaType.APPLICATION_JSON));

        GutendexClient.BookLookupResult result = client.lookupBook(13707);

        assertEquals(LookupStatus.FOUND, result.status());
        assertNotNull(result.book());
        assertEquals("Twice-told tales", result.book().title());
        server.verify();
    }

    @Test
    void lookupReportsNotFoundForAGutendex404() {
        server.expect(requestTo("https://gutendex.com/books/999999/"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"Not found.\"}"));

        GutendexClient.BookLookupResult result = client.lookupBook(999999);

        assertEquals(LookupStatus.NOT_FOUND, result.status());
        assertNull(result.book());
    }

    @Test
    void lookupReportsUnavailableForAnEmptySuccessfulResponse() {
        // 204 and a 200 with no body are upstream faults, not a confirmed "no such book".
        server.expect(requestTo("https://gutendex.com/books/13707/")).andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertEquals(LookupStatus.UNAVAILABLE, client.lookupBook(13707).status());
        server.reset();
        server.expect(requestTo("https://gutendex.com/books/13707/")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertEquals(LookupStatus.UNAVAILABLE, client.lookupBook(13707).status());
    }

    @Test
    void lookupReportsUnavailableForAServerError() {
        server.expect(requestTo("https://gutendex.com/books/13707/")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertEquals(LookupStatus.UNAVAILABLE, client.lookupBook(13707).status());
    }

    @Test
    void lookupReportsUnavailableForATimeoutInsteadOfHangingOrThrowing() {
        server.expect(requestTo("https://gutendex.com/books/13707/"))
            .andRespond(request -> { throw new SocketTimeoutException("Read timed out"); });

        assertEquals(LookupStatus.UNAVAILABLE, client.lookupBook(13707).status());
    }

    @Test
    void lookupReportsUnavailableWhenTheConnectionFails() {
        server.expect(requestTo("https://gutendex.com/books/13707/"))
            .andRespond(request -> { throw new IOException("Connection refused"); });

        assertEquals(LookupStatus.UNAVAILABLE, client.lookupBook(13707).status());
    }

    @Test
    void publicConstructorSendsRequestsToTheConfiguredBaseUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer configured = MockRestServiceServer.bindTo(builder).build();
        GutendexClient overridden = new GutendexClient(builder, "http://gutendex.example.test");
        configured.expect(requestTo("http://gutendex.example.test/books/5/")).andExpect(method(GET))
            .andRespond(withSuccess("{\"id\":5,\"title\":\"Five\",\"authors\":[],\"formats\":{},\"download_count\":1}",
                MediaType.APPLICATION_JSON));

        assertEquals("Five", overridden.getBook(5).orElseThrow().title());
        configured.verify();
    }
}
