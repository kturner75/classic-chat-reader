package com.classicchatreader.gutendex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GutendexBook(
    int id,
    String title,
    List<Author> authors,
    List<String> subjects,
    List<String> bookshelves,
    List<String> languages,
    Map<String, String> formats,
    @JsonProperty("download_count") int downloadCount
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(
        String name,
        @JsonProperty("birth_year") Integer birthYear,
        @JsonProperty("death_year") Integer deathYear
    ) {}

    public String getPrimaryAuthor() {
        if (authors == null || authors.isEmpty()) {
            return "Unknown";
        }
        return authors.get(0).name();
    }

    public String getHtmlUrl() {
        if (formats == null) return null;

        // First, try the direct "text/html" key which usually has the best format
        String directHtml = formats.get("text/html");
        if (directHtml != null) {
            return directHtml;
        }

        // Look for text/html with charset
        String bestUrl = null;
        int bestScore = -1;

        for (Map.Entry<String, String> entry : formats.entrySet()) {
            String key = entry.getKey();
            String url = entry.getValue();

            if (!key.startsWith("text/html")) continue;

            // Skip audiobook and other non-text content
            String lowerUrl = url.toLowerCase();
            if (lowerUrl.contains("audio") ||
                lowerUrl.contains("mp3") ||
                lowerUrl.contains("librivox") ||
                lowerUrl.contains("m4b")) {
                continue;
            }

            // Score the URL - higher is better
            int score = 0;

            // Prefer URLs with "images" (usually the nicest format)
            if (lowerUrl.contains("images")) {
                score += 10;
            }

            // Prefer the standard Gutenberg HTML format pattern: {id}-h.htm
            if (lowerUrl.contains("/" + id + "-h.htm") ||
                lowerUrl.contains("/" + id + "-h/")) {
                score += 5;
            }

            if (score > bestScore) {
                bestScore = score;
                bestUrl = url;
            }
        }

        return bestUrl;
    }

    public String getPlainTextUrl() {
        if (formats == null) return null;
        return formats.get("text/plain; charset=utf-8");
    }
}
