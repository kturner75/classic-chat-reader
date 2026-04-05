package org.example.reader.service;

import org.example.reader.entity.BookEntity;
import org.example.reader.entity.ChapterEntity;
import org.example.reader.entity.ParagraphEntity;
import org.example.reader.gutendex.GutenbergContentParser;
import org.example.reader.gutendex.GutenbergContentParser.ParsedBook;
import org.example.reader.gutendex.GutenbergContentParser.ParsedChapter;
import org.example.reader.gutendex.GutendexBook;
import org.example.reader.gutendex.GutendexClient;
import org.example.reader.gutendex.GutendexResponse;
import org.example.reader.model.Book;
import org.example.reader.service.CuratedCatalogService.CuratedCatalogBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BookImportService {

    private static final Logger log = LoggerFactory.getLogger(BookImportService.class);
    private static final String SOURCE_GUTENBERG = "gutenberg";

    private final CatalogMode catalogMode;
    private final GutendexClient gutendexClient;
    private final GutenbergContentParser contentParser;
    private final BookStorageService bookStorageService;
    private final CuratedCatalogService curatedCatalogService;

    @Autowired
    public BookImportService(GutendexClient gutendexClient,
                             GutenbergContentParser contentParser,
                             BookStorageService bookStorageService,
                             CuratedCatalogService curatedCatalogService,
                             @Value("${library.catalog.mode:curated}") String catalogMode) {
        this.gutendexClient = gutendexClient;
        this.contentParser = contentParser;
        this.bookStorageService = bookStorageService;
        this.curatedCatalogService = curatedCatalogService;
        this.catalogMode = CatalogMode.from(catalogMode);
        log.info(
                "Catalog discovery mode: {} (curatedBooks={})",
                this.catalogMode.toConfigValue(),
                curatedCatalogService.getPopularBooks().size()
        );
    }

    public record SearchResult(
        int gutenbergId,
        String title,
        String author,
        int downloadCount,
        List<String> subjects,
        List<String> bookshelves,
        boolean alreadyImported
    ) {}

    public record ImportResult(
        boolean success,
        String bookId,
        String message,
        int chapterCount,
        int paragraphCount
    ) {}

    public record CatalogModeStatus(
            String catalogMode,
            boolean curatedOnly,
            int curatedBookCount
    ) {}

    public CatalogModeStatus getCatalogModeStatus() {
        return new CatalogModeStatus(
                catalogMode.toConfigValue(),
                catalogMode == CatalogMode.CURATED,
                curatedCatalogService.getPopularBooks().size()
        );
    }

    public List<SearchResult> searchGutenberg(String query) {
        if (catalogMode == CatalogMode.CURATED) {
            return curatedCatalogService.search(query).stream()
                    .map(this::toSearchResult)
                    .toList();
        }

        GutendexResponse response = gutendexClient.searchBooks(query);
        return toSearchResults(response);
    }

    public List<SearchResult> getPopularBooks() {
        if (catalogMode == CatalogMode.CURATED) {
            return curatedCatalogService.getPopularBooks().stream()
                    .map(this::toSearchResult)
                    .toList();
        }

        GutendexResponse response = gutendexClient.getPopularBooks();
        return toSearchResults(response);
    }

    public List<SearchResult> getPopularBooks(int page) {
        if (catalogMode == CatalogMode.CURATED) {
            return curatedCatalogService.getPopularBooks().stream()
                    .map(this::toSearchResult)
                    .toList();
        }

        GutendexResponse response = gutendexClient.getPopularBooks(page);
        return toSearchResults(response);
    }

    private SearchResult toSearchResult(CuratedCatalogBook book) {
        boolean imported = bookStorageService.existsBySource(
                SOURCE_GUTENBERG,
                String.valueOf(book.gutenbergId())
        );

        return new SearchResult(
                book.gutenbergId(),
                book.title(),
                book.author(),
                book.downloadCount(),
                sanitizeMetadataList(book.subjects()),
                sanitizeMetadataList(book.bookshelves()),
                imported
        );
    }

    private List<SearchResult> toSearchResults(GutendexResponse response) {
        if (response == null || response.results() == null) {
            return List.of();
        }

        // Use a map to deduplicate by normalized title, keeping highest download count
        Map<String, SearchResult> deduped = new LinkedHashMap<>();

        for (GutendexBook book : response.results()) {
            // Only include English books with HTML available
            if (!book.languages().contains("en")) continue;
            if (book.getHtmlUrl() == null) continue;

            boolean imported = bookStorageService.existsBySource(
                SOURCE_GUTENBERG,
                String.valueOf(book.id())
            );

            SearchResult result = new SearchResult(
                book.id(),
                book.title(),
                book.getPrimaryAuthor(),
                book.downloadCount(),
                sanitizeMetadataList(book.subjects()),
                sanitizeMetadataList(book.bookshelves()),
                imported
            );

            // Normalize title for deduplication (lowercase, trim)
            String normalizedTitle = book.title().toLowerCase().trim();

            // Keep the version with highest download count (most popular)
            SearchResult existing = deduped.get(normalizedTitle);
            if (existing == null || book.downloadCount() > existing.downloadCount()) {
                deduped.put(normalizedTitle, result);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    private List<String> sanitizeMetadataList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .limit(8)
            .toList();
    }

    public ImportResult importBook(int gutenbergId) {
        // Check if already imported
        String sourceId = String.valueOf(gutenbergId);
        if (bookStorageService.existsBySource(SOURCE_GUTENBERG, sourceId)) {
            Optional<Book> existing = bookStorageService.findBySource(SOURCE_GUTENBERG, sourceId);
            return new ImportResult(
                false,
                existing.map(Book::id).orElse(null),
                "Book already imported",
                0, 0
            );
        }

        // Fetch book metadata
        Optional<GutendexBook> gutendexBook = gutendexClient.getBook(gutenbergId);
        if (gutendexBook.isEmpty()) {
            return new ImportResult(false, null, "Book not found in Gutenberg", 0, 0);
        }

        GutendexBook book = gutendexBook.get();
        String htmlUrl = book.getHtmlUrl();
        if (htmlUrl == null) {
            return new ImportResult(false, null, "No HTML version available", 0, 0);
        }

        // Fetch and parse content
        String html;
        try {
            html = gutendexClient.fetchContent(htmlUrl);
        } catch (Exception e) {
            return new ImportResult(false, null, "Failed to fetch content: " + e.getMessage(), 0, 0);
        }

        ParsedBook parsedBook = contentParser.parse(html);

        if (parsedBook.chapters().isEmpty()) {
            return new ImportResult(false, null, "No content could be parsed", 0, 0);
        }

        // Create entities
        BookEntity bookEntity = new BookEntity(book.title(), book.getPrimaryAuthor(), SOURCE_GUTENBERG);
        bookEntity.setSourceId(sourceId);
        bookEntity.setDescription(createDescription(book));
        if (curatedCatalogService.isCuratedGutenbergId(gutenbergId)) {
            // Curated imports should expose the supported reader features immediately.
            bookEntity.setTtsEnabled(true);
            bookEntity.setIllustrationEnabled(true);
            bookEntity.setCharacterEnabled(true);
        }

        int totalParagraphs = 0;
        int chapterIndex = 0;
        for (ParsedChapter chapter : parsedBook.chapters()) {
            ChapterEntity chapterEntity = new ChapterEntity(chapterIndex++, chapter.title());

            int paragraphIndex = 0;
            for (String paragraphText : chapter.paragraphs()) {
                chapterEntity.addParagraph(new ParagraphEntity(paragraphIndex++, paragraphText));
                totalParagraphs++;
            }

            bookEntity.addChapter(chapterEntity);
        }

        // Save to database
        Book savedBook = bookStorageService.saveBook(bookEntity);

        return new ImportResult(
            true,
            savedBook.id(),
            "Successfully imported",
            parsedBook.chapters().size(),
            totalParagraphs
        );
    }

    private String createDescription(GutendexBook book) {
        StringBuilder desc = new StringBuilder();

        if (book.subjects() != null && !book.subjects().isEmpty()) {
            desc.append(String.join(", ", book.subjects().subList(0, Math.min(3, book.subjects().size()))));
        }

        return desc.toString();
    }

    private enum CatalogMode {
        CURATED,
        FULL;

        private static CatalogMode from(String rawValue) {
            if (rawValue == null) {
                return CURATED;
            }
            String normalized = rawValue.trim().toLowerCase();
            return "full".equals(normalized) ? FULL : CURATED;
        }

        private String toConfigValue() {
            return this == FULL ? "full" : "curated";
        }
    }
}
