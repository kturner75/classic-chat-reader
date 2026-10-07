package com.classicchatreader.gutendex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Service
public class GutendexClient {

    private static final Logger log = LoggerFactory.getLogger(GutendexClient.class);
    // The ID preview is a courtesy to the operator, so it gives up quickly instead of hanging
    // when gutendex.com is slow. Search, popular and import keep their existing behavior.
    private static final Duration LOOKUP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LOOKUP_READ_TIMEOUT = Duration.ofSeconds(10);

    public enum LookupStatus { FOUND, NOT_FOUND, UNAVAILABLE }

    /** FOUND carries the book; NOT_FOUND is a real 404 from Gutendex; UNAVAILABLE is a timeout, outage or 5xx. */
    public record BookLookupResult(LookupStatus status, GutendexBook book) {
        public static BookLookupResult found(GutendexBook book) { return new BookLookupResult(LookupStatus.FOUND, book); }
        public static BookLookupResult notFound() { return new BookLookupResult(LookupStatus.NOT_FOUND, null); }
        public static BookLookupResult unavailable() { return new BookLookupResult(LookupStatus.UNAVAILABLE, null); }
    }

    private final RestClient restClient;
    private final RestClient lookupClient;

    // Spring needs to be told which constructor to use now that the test-only one exists.
    @Autowired
    public GutendexClient(RestClient.Builder restClientBuilder,
                          @Value("${gutendex.base-url}") String baseUrl) {
        // The lookup client is cloned from the injected builder so it keeps Boot's customizers.
        // gutendex.base-url exists so end-to-end runs can point CCR at a local stand-in for Gutendex.
        this(restClientBuilder.baseUrl(baseUrl).build(),
            shortTimeoutClient(restClientBuilder, baseUrl, LOOKUP_CONNECT_TIMEOUT, LOOKUP_READ_TIMEOUT));
    }

    // Visible for testing: lets a test bind both clients to mock servers.
    GutendexClient(RestClient restClient, RestClient lookupClient) {
        this.restClient = restClient;
        this.lookupClient = lookupClient;
    }

    // Visible for testing: a real client with real timeouts pointed at a local stalled server.
    static RestClient shortTimeoutClient(RestClient.Builder base, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return base.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Metadata lookup for the ID preview. Never throws; tells "no such book" apart from "could not reach Gutenberg". */
    public BookLookupResult lookupBook(int gutenbergId) {
        try {
            GutendexBook book = lookupClient.get()
                .uri("/books/{id}/", gutenbergId)
                .retrieve()
                .body(GutendexBook.class);
            // An empty successful response (204, or a 200 with no body) is an upstream fault, not a
            // Gutenberg "no such book". Only a real 404 is NOT_FOUND.
            if (book == null) {
                log.warn("event=gutendex_lookup_empty_body gutenbergId={}", gutenbergId);
                return BookLookupResult.unavailable();
            }
            return BookLookupResult.found(book);
        } catch (HttpClientErrorException.NotFound e) {
            return BookLookupResult.notFound();
        } catch (Exception e) {
            log.warn("event=gutendex_lookup_unavailable gutenbergId={} reason={}", gutenbergId, e.toString());
            return BookLookupResult.unavailable();
        }
    }

    public GutendexResponse searchBooks(String query) {
        return restClient.get()
            .uri("/books/?search={query}", query)
            .retrieve()
            .body(GutendexResponse.class);
    }

    public GutendexResponse searchBooks(String query, int page) {
        return restClient.get()
            .uri("/books/?search={query}&page={page}", query, page)
            .retrieve()
            .body(GutendexResponse.class);
    }

    public Optional<GutendexBook> getBook(int gutenbergId) {
        try {
            GutendexBook book = restClient.get()
                .uri("/books/{id}/", gutenbergId)
                .retrieve()
                .body(GutendexBook.class);
            return Optional.ofNullable(book);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public GutendexResponse getPopularBooks() {
        return restClient.get()
            .uri("/books/?sort=popular")
            .retrieve()
            .body(GutendexResponse.class);
    }

    public GutendexResponse getPopularBooks(int page) {
        return restClient.get()
            .uri("/books/?sort=popular&page={page}", page)
            .retrieve()
            .body(GutendexResponse.class);
    }

    private static final Duration CONTENT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONTENT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    public String fetchContent(String url) {
        // Use Java HttpClient which follows redirects
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(CONTENT_CONNECT_TIMEOUT)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(CONTENT_REQUEST_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // An error page is not the book: parsing it would report "no chapters" or an empty contents list.
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("Failed to fetch content from " + url + " (HTTP " + response.statusCode() + ")");
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch content from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted fetching content from " + url, e);
        }
    }
}
