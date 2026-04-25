package com.classicchatreader.controller;

import com.classicchatreader.service.PreGenerationService;
import com.classicchatreader.service.PreGenerationService.PreGenResult;
import com.classicchatreader.service.PreGenerationJobService;
import com.classicchatreader.service.PreGenerationJobService.PreGenJobStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for triggering pre-generation of book assets.
 * Supports both legacy blocking routes and async job-based routes.
 */
@RestController
@RequestMapping("/api/pregen")
public class PreGenerationController {

    private final PreGenerationService preGenerationService;
    private final PreGenerationJobService preGenerationJobService;
    private final boolean cacheOnly;

    public PreGenerationController(PreGenerationService preGenerationService,
                                   PreGenerationJobService preGenerationJobService,
                                   @org.springframework.beans.factory.annotation.Value("${generation.cache-only:false}")
                                   boolean cacheOnly) {
        this.preGenerationService = preGenerationService;
        this.preGenerationJobService = preGenerationJobService;
        this.cacheOnly = cacheOnly;
    }

    /**
     * Pre-generate all assets (illustrations and character portraits) for a book.
     * This is a blocking operation that will wait for all generation to complete.
     *
     * @param bookId The book ID to generate assets for
     * @return Result containing generation statistics
     */
    @PostMapping("/book/{bookId}")
    public ResponseEntity<PreGenResult> preGenerateForBook(@PathVariable String bookId) {
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        PreGenResult result = preGenerationService.preGenerateForBook(bookId);

        if (result.success()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * Pre-generate all assets for a book by its Gutenberg ID.
     * Will import the book if not already present.
     * This is a blocking operation that will wait for all generation to complete.
     *
     * @param gutenbergId The Project Gutenberg book ID
     * @return Result containing generation statistics
     */
    @PostMapping("/gutenberg/{gutenbergId}")
    public ResponseEntity<PreGenResult> preGenerateByGutenbergId(@PathVariable int gutenbergId) {
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        PreGenResult result = preGenerationService.preGenerateByGutenbergId(gutenbergId);

        if (result.success()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/jobs/book/{bookId}")
    public ResponseEntity<PreGenJobStatus> startJobForBook(@PathVariable String bookId) {
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        PreGenJobStatus jobStatus = preGenerationJobService.startBookJob(bookId);
        return ResponseEntity.accepted().body(jobStatus);
    }

    @PostMapping("/jobs/gutenberg/{gutenbergId}")
    public ResponseEntity<PreGenJobStatus> startJobForGutenbergId(@PathVariable int gutenbergId) {
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        PreGenJobStatus jobStatus = preGenerationJobService.startGutenbergJob(gutenbergId);
        return ResponseEntity.accepted().body(jobStatus);
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<PreGenJobStatus> getJobStatus(@PathVariable String jobId) {
        return preGenerationJobService.getJobStatus(jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<PreGenJobStatus> cancelJob(@PathVariable String jobId) {
        return cancelJobInternal(jobId);
    }

    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<PreGenJobStatus> cancelJobDelete(@PathVariable String jobId) {
        return cancelJobInternal(jobId);
    }

    private ResponseEntity<PreGenJobStatus> cancelJobInternal(String jobId) {
        return preGenerationJobService.cancelJob(jobId)
                .map(job -> ResponseEntity.accepted().body(job))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
