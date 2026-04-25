package com.classicchatreader.gutendex;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

@Service
public class GutendexClient {

    private static final String BASE_URL = "https://gutendex.com";

    private final RestClient restClient;

    public GutendexClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
            .baseUrl(BASE_URL)
            .build();
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

    public String fetchContent(String url) {
        // Use Java HttpClient which follows redirects
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch content from " + url, e);
        }
    }
}
