package com.classicchatreader.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.classicchatreader.model.Book;
import com.classicchatreader.model.BookmarkedParagraph;
import com.classicchatreader.model.ChapterContent;
import com.classicchatreader.model.ParagraphAnnotation;
import com.classicchatreader.service.BookCoverService;
import com.classicchatreader.service.BookStorageService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.ParagraphAnnotationService;
import com.classicchatreader.service.ReaderIdentityService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final BookStorageService bookStorageService;
    private final BookCoverService bookCoverService;
    private final CdnAssetService cdnAssetService;
    private final ParagraphAnnotationService paragraphAnnotationService;
    private final ReaderIdentityService readerIdentityService;
    private final boolean bookCoverCdnEnabled;

    public LibraryController(
            BookStorageService bookStorageService,
            BookCoverService bookCoverService,
            CdnAssetService cdnAssetService,
            ParagraphAnnotationService paragraphAnnotationService,
            ReaderIdentityService readerIdentityService,
            @org.springframework.beans.factory.annotation.Value("${book-cover.cdn.enabled:false}") boolean bookCoverCdnEnabled) {
        this.bookStorageService = bookStorageService;
        this.bookCoverService = bookCoverService;
        this.cdnAssetService = cdnAssetService;
        this.paragraphAnnotationService = paragraphAnnotationService;
        this.readerIdentityService = readerIdentityService;
        this.bookCoverCdnEnabled = bookCoverCdnEnabled;
    }

    @GetMapping
    public List<Book> listBooks() {
        return bookStorageService.getAllBooks();
    }

    @GetMapping("/{bookId}")
    public ResponseEntity<Book> getBook(@PathVariable String bookId) {
        return bookStorageService.getBook(bookId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{bookId}/cover")
    public ResponseEntity<byte[]> getBookCover(@PathVariable String bookId) {
        if (bookCoverCdnEnabled && cdnAssetService.isEnabled()) {
            return bookCoverService.getCoverFilename(bookId)
                    .flatMap(key -> cdnAssetService.buildAssetUrl("book-covers", key))
                    .map(url -> ResponseEntity.status(302)
                            .header(HttpHeaders.LOCATION, url)
                            .body(new byte[0]))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }

        byte[] image = bookCoverService.getCover(bookId);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/png")
                .header(HttpHeaders.CACHE_CONTROL, "max-age=604800")
                .body(image);
    }

    @GetMapping("/{bookId}/cover/status")
    public CoverStatusResponse getBookCoverStatus(@PathVariable String bookId) {
        return toCoverStatusResponse(bookId);
    }

    @PostMapping("/{bookId}/cover/request")
    public ResponseEntity<CoverStatusResponse> requestBookCover(@PathVariable String bookId) {
        if (bookStorageService.getBook(bookId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        bookCoverService.requestCover(bookId);
        return ResponseEntity.accepted().body(getBookCoverStatus(bookId));
    }

    @PostMapping("/{bookId}/cover/retry")
    public ResponseEntity<CoverStatusResponse> retryBookCover(
            @PathVariable String bookId,
            @RequestBody(required = false) CoverRetryRequest request) {
        if (bookStorageService.getBook(bookId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean queued = bookCoverService.retryCover(bookId, request == null ? null : request.prompt());
        if (!queued) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(toCoverStatusResponse(bookId));
        }
        return ResponseEntity.accepted().body(toCoverStatusResponse(bookId));
    }

    @PutMapping(path = "/{bookId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CoverStatusResponse> uploadBookCover(
            @PathVariable String bookId,
            @RequestParam("file") MultipartFile file) throws IOException {
        if (bookStorageService.getBook(bookId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            bookCoverService.saveManualCover(bookId, file.getBytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new CoverStatusResponse(
                    bookId,
                    "INVALID",
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    e.getMessage()
            ));
        }
        return ResponseEntity.ok(toCoverStatusResponse(bookId));
    }

    @GetMapping("/{bookId}/citation/mla")
    public ResponseEntity<CitationResponse> getMlaCitation(@PathVariable String bookId,
                                                           @RequestParam(required = false) String chapterId,
                                                           @RequestParam(required = false) Integer paragraphIndex,
                                                           HttpServletRequest request) {
        String siteBaseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getContextPath() == null || request.getContextPath().isBlank()
                        ? "/"
                        : request.getContextPath() + "/")
                .replaceQuery(null)
                .build()
                .toUriString();
        return bookStorageService.getMlaCitation(bookId, siteBaseUrl, chapterId, paragraphIndex)
                .map(citation -> ResponseEntity.ok(new CitationResponse(citation)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{bookId}/features")
    public ResponseEntity<Book> updateBookFeatures(
            @PathVariable String bookId,
            @RequestBody FeatureUpdateRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }

        return bookStorageService.updateBookFeatures(
                bookId,
                request.ttsEnabled(),
                request.illustrationEnabled(),
                request.characterEnabled()
        ).map(ResponseEntity::ok)
         .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{bookId}/chapters/{chapterId}")
    public ResponseEntity<ChapterContent> getChapterContent(
            @PathVariable String bookId,
            @PathVariable String chapterId) {
        return bookStorageService.getChapterContent(bookId, chapterId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{bookId}/annotations")
    public ResponseEntity<List<ParagraphAnnotation>> getAnnotations(
            @PathVariable String bookId,
            HttpServletRequest request,
            HttpServletResponse response) {
        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        return paragraphAnnotationService.getBookAnnotations(identity.readerKey(), identity.userId(), bookId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{bookId}/bookmarks")
    public ResponseEntity<List<BookmarkedParagraph>> getBookmarks(
            @PathVariable String bookId,
            HttpServletRequest request,
            HttpServletResponse response) {
        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        return paragraphAnnotationService.getBookmarkedParagraphs(identity.readerKey(), identity.userId(), bookId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{bookId}/annotations/{chapterId}/{paragraphIndex}")
    public ResponseEntity<ParagraphAnnotation> upsertAnnotation(
            @PathVariable String bookId,
            @PathVariable String chapterId,
            @PathVariable int paragraphIndex,
            @RequestBody AnnotationUpsertRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }

        boolean highlighted = Boolean.TRUE.equals(request.highlighted());
        boolean bookmarked = Boolean.TRUE.equals(request.bookmarked());
        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(httpRequest, httpResponse);
        ParagraphAnnotationService.SaveOutcome outcome = paragraphAnnotationService.saveAnnotation(
                identity.readerKey(),
                identity.userId(),
                bookId,
                chapterId,
                paragraphIndex,
                highlighted,
                request.noteText(),
                bookmarked
        );

        if (outcome.status() == ParagraphAnnotationService.SaveStatus.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        if (outcome.status() == ParagraphAnnotationService.SaveStatus.CLEARED) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(outcome.annotation());
    }

    @DeleteMapping("/{bookId}/annotations/{chapterId}/{paragraphIndex}")
    public ResponseEntity<Void> deleteAnnotation(
            @PathVariable String bookId,
            @PathVariable String chapterId,
            @PathVariable int paragraphIndex,
            HttpServletRequest request,
            HttpServletResponse response) {
        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        ParagraphAnnotationService.DeleteStatus status = paragraphAnnotationService.deleteAnnotation(
                identity.readerKey(),
                identity.userId(),
                bookId,
                chapterId,
                paragraphIndex
        );
        if (status == ParagraphAnnotationService.DeleteStatus.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{bookId}")
    public ResponseEntity<Void> deleteBook(@PathVariable String bookId) {
        boolean deleted = bookStorageService.deleteBook(bookId);
        if (deleted) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping
    public ResponseEntity<DeleteAllResponse> deleteAllBooks() {
        int count = bookStorageService.deleteAllBooks();
        return ResponseEntity.ok(new DeleteAllResponse(count));
    }

    public record DeleteAllResponse(int deletedCount) {}

    public record CitationResponse(String citation) {}

    private CoverStatusResponse toCoverStatusResponse(String bookId) {
        return bookCoverService.getCoverStatus(bookId)
                .map(status -> new CoverStatusResponse(
                        bookId,
                        status.status() == null ? "NONE" : status.status().name(),
                        status.status() == com.classicchatreader.entity.IllustrationStatus.COMPLETED,
                        status.imageFilename() != null && !status.imageFilename().isBlank(),
                        status.imageFilename() == null || status.imageFilename().isBlank()
                                ? null
                                : buildCoverUrl(bookId, status.completedAt()),
                        status.generatedPrompt(),
                        status.promptOverride(),
                        status.coverSource(),
                        status.errorMessage()
                ))
                .orElseGet(() -> new CoverStatusResponse(
                        bookId,
                        "NONE",
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null
                ));
    }

    private String buildCoverUrl(String bookId, java.time.LocalDateTime completedAt) {
        String baseUrl = "/api/library/" + bookId + "/cover";
        if (completedAt == null) {
            return baseUrl;
        }
        String version = completedAt.toString().replaceAll("[^0-9]", "");
        return version.isBlank() ? baseUrl : baseUrl + "?v=" + version;
    }

    public record CoverStatusResponse(
            String bookId,
            String status,
            boolean ready,
            boolean hasImage,
            String coverUrl,
            String generatedPrompt,
            String promptOverride,
            String coverSource,
            String errorMessage
    ) {}

    public record CoverRetryRequest(String prompt) {}

    public record FeatureUpdateRequest(
            Boolean ttsEnabled,
            Boolean illustrationEnabled,
            Boolean characterEnabled
    ) {}

    public record AnnotationUpsertRequest(
            Boolean highlighted,
            String noteText,
            Boolean bookmarked
    ) {}
}
