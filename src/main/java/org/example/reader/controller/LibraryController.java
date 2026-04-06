package org.example.reader.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.reader.model.Book;
import org.example.reader.model.BookmarkedParagraph;
import org.example.reader.model.ChapterContent;
import org.example.reader.model.ParagraphAnnotation;
import org.example.reader.service.BookStorageService;
import org.example.reader.service.ParagraphAnnotationService;
import org.example.reader.service.ReaderIdentityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final BookStorageService bookStorageService;
    private final ParagraphAnnotationService paragraphAnnotationService;
    private final ReaderIdentityService readerIdentityService;

    public LibraryController(
            BookStorageService bookStorageService,
            ParagraphAnnotationService paragraphAnnotationService,
            ReaderIdentityService readerIdentityService) {
        this.bookStorageService = bookStorageService;
        this.paragraphAnnotationService = paragraphAnnotationService;
        this.readerIdentityService = readerIdentityService;
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
