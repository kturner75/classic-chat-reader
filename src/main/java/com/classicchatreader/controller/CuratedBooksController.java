package com.classicchatreader.controller;

import com.classicchatreader.service.CuratedBookStore;
import com.classicchatreader.service.CuratedCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Curated landing catalog membership (BL-072) for the local Studio. Listing and unlisting change
 * membership only; covers, portraits, illustrations and rosters are never touched.
 */
@RestController
@RequestMapping("/api/curated-books")
public class CuratedBooksController {

    private final CuratedCatalogService catalog;

    public CuratedBooksController(CuratedCatalogService catalog) {
        this.catalog = catalog;
    }

    public record CuratedBookResponse(String source, String sourceId, String title, String author, int popularity,
                                      List<String> subjects, List<String> bookshelves, List<String> aliases,
                                      String status) {
        static CuratedBookResponse of(CuratedBookStore.Entry entry) {
            var book = entry.book();
            return new CuratedBookResponse(CuratedBookStore.SOURCE_GUTENBERG, String.valueOf(book.gutenbergId()),
                    book.title(), book.author(), book.downloadCount(), book.subjects(), book.bookshelves(),
                    book.aliases(), entry.status());
        }
    }

    public record AddRequest(String source, String sourceId, String title, String author, Integer popularity,
                             List<String> subjects, List<String> bookshelves, List<String> aliases) {}

    public record StatusRequest(String status) {}

    @GetMapping
    public List<CuratedBookResponse> list(HttpServletRequest request) {
        LocalStudioAccess.require(request);
        return catalog.listMembership().stream().map(CuratedBookResponse::of).toList();
    }

    /** Adds a title, or reactivates it. 201 when the row is new, 200 when it already existed. */
    @PostMapping
    public ResponseEntity<CuratedBookResponse> add(@RequestBody AddRequest body, HttpServletRequest request) {
        LocalStudioAccess.require(request);
        if (body == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        CuratedCatalogService.AddResult result = catalog.add(gutenbergId(body.source(), body.sourceId()),
                body.title(), body.author(), body.popularity(), body.subjects(), body.bookshelves(), body.aliases());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(CuratedBookResponse.of(result.entry()));
    }

    @PatchMapping("/{source}/{sourceId}")
    public CuratedBookResponse setStatus(@PathVariable String source, @PathVariable String sourceId,
                                         @RequestBody StatusRequest body, HttpServletRequest request) {
        LocalStudioAccess.require(request);
        return catalog.setStatus(gutenbergId(source, sourceId), body == null ? null : body.status())
                .map(CuratedBookResponse::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a curated book: " + source + "/" + sourceId));
    }

    private static int gutenbergId(String source, String sourceId) {
        if (!CuratedBookStore.SOURCE_GUTENBERG.equals(source)) {
            throw new IllegalArgumentException("source must be \"gutenberg\"");
        }
        try {
            return Integer.parseInt(sourceId == null ? "" : sourceId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sourceId must be a Gutenberg number");
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "That book was added concurrently; retry"));
    }
}
