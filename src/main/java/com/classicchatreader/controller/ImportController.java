package com.classicchatreader.controller;

import com.classicchatreader.service.BookImportService;
import com.classicchatreader.service.BookImportService.CatalogModeStatus;
import com.classicchatreader.service.BookImportService.ContentsOutcome;
import com.classicchatreader.service.BookImportService.ImportResult;
import com.classicchatreader.service.BookImportService.LookupOutcome;
import com.classicchatreader.service.BookImportService.SearchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final BookImportService bookImportService;

    public ImportController(BookImportService bookImportService) {
        this.bookImportService = bookImportService;
    }

    @GetMapping("/search")
    public List<SearchResult> searchGutenberg(@RequestParam String q) {
        return bookImportService.searchGutenberg(q);
    }

    @GetMapping("/popular")
    public List<SearchResult> getPopularBooks(@RequestParam(defaultValue = "1") int page) {
        return bookImportService.getPopularBooks(page);
    }

    @GetMapping("/catalog-mode")
    public CatalogModeStatus getCatalogMode() {
        return bookImportService.getCatalogModeStatus();
    }

    /**
     * Read-only preview of a Gutenberg ID (title, author, importable, already imported).
     * 404 {@code gutenberg_not_found}: Gutenberg has no such book. 503 {@code gutenberg_unavailable}:
     * it could not be reached in time, so the ID is unconfirmed rather than wrong.
     */
    @GetMapping("/gutenberg/{gutenbergId}")
    public ResponseEntity<?> lookupBook(@PathVariable int gutenbergId) {
        LookupOutcome outcome = bookImportService.lookupGutenberg(gutenbergId);
        return switch (outcome.status()) {
            case FOUND -> ResponseEntity.ok(outcome.lookup());
            case NOT_FOUND -> ResponseEntity.status(404).body(Map.of(
                "error", "gutenberg_not_found",
                "message", "Gutenberg has no book with ID " + gutenbergId));
            case UNAVAILABLE -> ResponseEntity.status(503).body(Map.of(
                "error", "gutenberg_unavailable",
                "message", "Could not reach Gutenberg to check ID " + gutenbergId + ". Try again shortly."));
        };
    }

    /**
     * Read-only: the contents list Gutenberg prints for this book (entries may be empty when the
     * edition has none). 404 {@code gutenberg_not_found}; 503 {@code gutenberg_unavailable}, as the
     * lookup above. Operator-only (see SensitiveApiRequestMatcher): it downloads the whole book.
     */
    @GetMapping("/gutenberg/{gutenbergId}/contents")
    public ResponseEntity<?> gutenbergContents(@PathVariable int gutenbergId) {
        ContentsOutcome outcome = bookImportService.getGutenbergContents(gutenbergId);
        return switch (outcome.status()) {
            case FOUND -> ResponseEntity.ok(Map.of("gutenbergId", gutenbergId, "entries", outcome.entries()));
            case NOT_FOUND -> ResponseEntity.status(404).body(Map.of(
                "error", "gutenberg_not_found",
                "message", "Gutenberg has no book with ID " + gutenbergId));
            case UNAVAILABLE -> ResponseEntity.status(503).body(Map.of(
                "error", "gutenberg_unavailable",
                "message", "Could not read the contents of Gutenberg book " + gutenbergId + ". Try again shortly."));
        };
    }

    @PostMapping("/gutenberg/{gutenbergId}")
    public ResponseEntity<ImportResult> importBook(@PathVariable int gutenbergId) {
        ImportResult result = bookImportService.importBook(gutenbergId);

        if (result.success()) {
            return ResponseEntity.ok(result);
        } else if (result.message().equals("Book already imported")) {
            return ResponseEntity.status(409).body(result); // Conflict
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }
}
