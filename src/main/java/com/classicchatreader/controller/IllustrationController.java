package com.classicchatreader.controller;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.IllustrationStatus;
import com.classicchatreader.model.IllustrationSettings;
import com.classicchatreader.model.IllustrationStyleSuggestions;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.service.ComfyUIService;
import com.classicchatreader.service.CdnAssetService;
import com.classicchatreader.service.IllustrationService;
import com.classicchatreader.service.IllustrationStyleAnalysisService;
import com.classicchatreader.service.LiveAssetUploads;
import com.classicchatreader.service.LiveAssetWriteResult;
import com.classicchatreader.service.UnsupportedImageTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/illustrations")
public class IllustrationController {

    private static final Logger log = LoggerFactory.getLogger(IllustrationController.class);

    @Value("${illustration.allow-prompt-editing:false}")
    private boolean allowPromptEditing;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    @Value("${illustration.cdn.enabled:false}")
    private boolean illustrationCdnEnabled;

    private final IllustrationService illustrationService;
    private final IllustrationStyleAnalysisService styleAnalysisService;
    private final ComfyUIService comfyUIService;
    private final CdnAssetService cdnAssetService;
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;

    public IllustrationController(
            IllustrationService illustrationService,
            IllustrationStyleAnalysisService styleAnalysisService,
            ComfyUIService comfyUIService,
            CdnAssetService cdnAssetService,
            BookRepository bookRepository,
            ChapterRepository chapterRepository) {
        this.illustrationService = illustrationService;
        this.styleAnalysisService = styleAnalysisService;
        this.comfyUIService = comfyUIService;
        this.cdnAssetService = cdnAssetService;
        this.bookRepository = bookRepository;
        this.chapterRepository = chapterRepository;
    }

    /**
     * Check if illustration services are available.
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("comfyuiAvailable", comfyUIService.isAvailable());
        status.put("ollamaAvailable", styleAnalysisService.isOllamaAvailable());
        status.put("allowPromptEditing", allowPromptEditing);
        status.put("queueProcessorRunning", illustrationService.isQueueProcessorRunning());
        status.put("cacheOnly", cacheOnly);
        return status;
    }

    /**
     * Get saved illustration style settings for a book.
     */
    @GetMapping("/settings/{bookId}")
    public ResponseEntity<IllustrationSettings> getStyleSettings(@PathVariable String bookId) {
        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        BookEntity book = bookOpt.get();
        if (!isIllustrationEnabled(book)) {
            return ResponseEntity.status(403).build();
        }

        if (book.getIllustrationStyle() != null) {
            IllustrationSettings settings = new IllustrationSettings(
                    book.getIllustrationStyle(),
                    book.getIllustrationPromptPrefix(),
                    book.getIllustrationSetting(),
                    book.getIllustrationStyleReasoning(),
                    book.getIllustrationCoverSubject(),
                    book.getIllustrationCoverFocus()
            );
            return ResponseEntity.ok(settings);
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * Operator override of the book-wide Imagine style used by portraits, covers, and chapter plates.
     */
    @PutMapping("/settings/{bookId}")
    public ResponseEntity<IllustrationSettings> putStyleSettings(
            @PathVariable String bookId,
            @RequestBody IllustrationSettings body) {
        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(403).build();
        }
        IllustrationSettings saved = illustrationService.updateBookStyle(bookId, body);
        if (saved == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(saved);
    }

    /**
     * AI suggestions for book-wide Imagine style. Does not persist; operator picks one and PUT /settings.
     */
    @PostMapping("/settings/{bookId}/suggestions")
    public ResponseEntity<IllustrationStyleSuggestions> suggestStyles(
            @PathVariable String bookId,
            @RequestParam(required = false, defaultValue = "4") int limit) {
        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(403).build();
        }
        IllustrationStyleSuggestions suggestions = illustrationService.suggestBookStyles(bookId, limit);
        if (suggestions == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(suggestions);
    }

    /**
     * Analyze book and determine illustration style.
     */
    @PostMapping("/analyze/{bookId}")
    public ResponseEntity<IllustrationSettings> analyzeBook(
            @PathVariable String bookId,
            @RequestParam(required = false, defaultValue = "false") boolean force) {

        Optional<BookEntity> bookOpt = bookRepository.findById(bookId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(403).build();
        }

        IllustrationSettings settings = illustrationService.getOrAnalyzeBookStyle(bookId, force);

        log.info("Analyzed illustration style for book {}: {}",
                bookOpt.get().getTitle(), settings.style());

        return ResponseEntity.ok(settings);
    }

    /**
     * Get the illustration image for a chapter.
     */
    @GetMapping("/chapter/{chapterId}")
    public ResponseEntity<byte[]> getIllustration(@PathVariable String chapterId) {
        Optional<BookEntity> bookOpt = getBookForChapter(chapterId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(403).build();
        }

        byte[] image = illustrationService.getIllustration(chapterId);
        if (image != null) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/png")
                    .cacheControl(CacheControl.noCache().cachePrivate())
                    .body(image);
        }

        if (illustrationCdnEnabled && cdnAssetService.isEnabled()) {
            return illustrationService.getIllustrationAsset(chapterId)
                    .flatMap(asset -> cdnAssetService.buildAssetUrl("illustrations", asset))
                    .map(url -> ResponseEntity.status(302)
                            .header(HttpHeaders.LOCATION, url)
                            .body(new byte[0]))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }

        return ResponseEntity.notFound().build();
    }

    /**
     * Get the status of illustration generation for a chapter.
     */
    @GetMapping("/chapter/{chapterId}/status")
    public Map<String, Object> getChapterStatus(@PathVariable String chapterId) {
        Optional<BookEntity> bookOpt = getBookForChapter(chapterId);
        if (bookOpt.isEmpty()) {
            return Map.of(
                    "chapterId", chapterId,
                    "status", "NOT_FOUND",
                    "ready", false
            );
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return Map.of(
                    "chapterId", chapterId,
                    "status", "DISABLED",
                    "ready", false
            );
        }
        IllustrationStatus status = illustrationService.getStatus(chapterId);
        if (cacheOnly) {
            boolean stableAssetReady = illustrationService.restoreCachedIllustrationIfPresent(chapterId);
            if (stableAssetReady) {
                status = IllustrationStatus.COMPLETED;
            } else if (status == IllustrationStatus.COMPLETED) {
                status = null;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("chapterId", chapterId);
        response.put("status", status != null ? status.name() : "NOT_REQUESTED");
        response.put("ready", status == IllustrationStatus.COMPLETED);
        response.put("generatedPrompt", illustrationService.getPrompt(chapterId));

        return response;
    }

    /**
     * Replace the live chapter illustration without enqueueing generation.
     */
    @PutMapping(path = "/chapter/{chapterId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadIllustration(
            @PathVariable String chapterId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "generated_prompt", required = false) String generatedPrompt,
            @RequestParam(value = "prompt_override", required = false) String promptOverride) throws java.io.IOException {
        Optional<BookEntity> bookOpt = getBookForChapter(chapterId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(getChapterStatus(chapterId));
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            LiveAssetWriteResult result = illustrationService.saveUploadedIllustration(
                    chapterId,
                    file.getBytes(),
                    source,
                    generatedPrompt,
                    promptOverride);
            if (result == LiveAssetWriteResult.CACHE_ONLY
                    || result == LiveAssetWriteResult.GENERATION_IN_PROGRESS) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(getChapterStatus(chapterId));
            }
            if (result == LiveAssetWriteResult.NOT_FOUND) {
                return ResponseEntity.notFound().build();
            }
        } catch (UnsupportedImageTypeException e) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of(
                    "chapterId", chapterId,
                    "status", "INVALID",
                    "ready", false,
                    "errorMessage", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "chapterId", chapterId,
                    "status", "INVALID",
                    "ready", false,
                    "errorMessage", e.getMessage()
            ));
        }
        Map<String, Object> response = new HashMap<>(getChapterStatus(chapterId));
        response.put("source", LiveAssetUploads.resolveSource(source));
        return ResponseEntity.ok(response);
    }

    /**
     * Request illustration generation for a chapter.
     */
    @PostMapping("/chapter/{chapterId}/request")
    public ResponseEntity<Void> requestIllustration(@PathVariable String chapterId) {
        Optional<BookEntity> bookOpt = getBookForChapter(chapterId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(403).build();
        }
        illustrationService.requestIllustration(chapterId);
        return ResponseEntity.accepted().build();
    }

    /**
     * Pre-fetch the next chapter's illustration.
     */
    @PostMapping("/chapter/{chapterId}/prefetch-next")
    public ResponseEntity<Void> prefetchNext(@PathVariable String chapterId) {
        Optional<BookEntity> bookOpt = getBookForChapter(chapterId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(403).build();
        }
        illustrationService.prefetchNextChapter(chapterId);
        return ResponseEntity.accepted().build();
    }

    /**
     * Get the prompt used for an illustration.
     */
    @GetMapping("/chapter/{chapterId}/prompt")
    public ResponseEntity<Map<String, String>> getPrompt(@PathVariable String chapterId) {
        if (!allowPromptEditing) {
            return ResponseEntity.status(403).build();
        }
        Optional<BookEntity> bookOpt = getBookForChapter(chapterId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(403).build();
        }

        String prompt = illustrationService.getPrompt(chapterId);
        if (prompt == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of("prompt", prompt));
    }

    /**
     * Regenerate illustration with a custom prompt.
     */
    @PostMapping("/chapter/{chapterId}/regenerate")
    public ResponseEntity<Void> regenerate(
            @PathVariable String chapterId,
            @RequestBody(required = false) RegenerateRequest request) {

        if (!allowPromptEditing) {
            return ResponseEntity.status(403).build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        Optional<BookEntity> bookOpt = getBookForChapter(chapterId);
        if (bookOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!isIllustrationEnabled(bookOpt.get())) {
            return ResponseEntity.status(403).build();
        }

        String prompt = request == null ? null : request.prompt();
        illustrationService.regenerateWithPrompt(chapterId, prompt);
        return ResponseEntity.accepted().build();
    }

    /**
     * Retry stuck PENDING illustrations.
     */
    @PostMapping("/retry-stuck")
    public ResponseEntity<Void> retryStuck() {
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        illustrationService.retryStuckPendingIllustrations();
        return ResponseEntity.accepted().build();
    }

    public record RegenerateRequest(String prompt) {}

    private boolean isIllustrationEnabled(BookEntity book) {
        return Boolean.TRUE.equals(book.getIllustrationEnabled());
    }

    private Optional<BookEntity> getBookForChapter(String chapterId) {
        return chapterRepository.findById(chapterId)
                .map(chapter -> chapter.getBook());
    }
}
