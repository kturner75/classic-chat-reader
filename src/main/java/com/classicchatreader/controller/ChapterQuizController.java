package com.classicchatreader.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.classicchatreader.config.RequestCorrelation;
import com.classicchatreader.model.ChapterQuizGradeResponse;
import com.classicchatreader.model.ChapterQuizResponse;
import com.classicchatreader.model.ChapterQuizStatusResponse;
import com.classicchatreader.model.QuizTrophy;
import com.classicchatreader.service.ChapterQuizService;
import com.classicchatreader.service.QuizMetricsService;
import com.classicchatreader.service.QuizProgressService;
import com.classicchatreader.service.ReaderIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quizzes")
public class ChapterQuizController {

    private static final Logger log = LoggerFactory.getLogger(ChapterQuizController.class);

    @Value("${quiz.enabled:true}")
    private boolean quizEnabled;

    @Value("${ai.reasoning.enabled:true}")
    private boolean reasoningEnabled;

    @Value("${generation.cache-only:false}")
    private boolean cacheOnly;

    @Value("${quiz.difficulty.ramp.enabled:true}")
    private boolean difficultyRampEnabled;

    @Value("${quiz.difficulty.ramp.chapter-step:6}")
    private int difficultyRampChapterStep;

    @Value("${quiz.difficulty.ramp.max-level:3}")
    private int difficultyRampMaxLevel;

    private final ChapterQuizService chapterQuizService;
    private final QuizProgressService quizProgressService;
    private final QuizMetricsService quizMetricsService;
    private final ReaderIdentityService readerIdentityService;

    public ChapterQuizController(
            ChapterQuizService chapterQuizService,
            QuizProgressService quizProgressService,
            QuizMetricsService quizMetricsService,
            ReaderIdentityService readerIdentityService) {
        this.chapterQuizService = chapterQuizService;
        this.quizProgressService = quizProgressService;
        this.quizMetricsService = quizMetricsService;
        this.readerIdentityService = readerIdentityService;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", quizEnabled);
        status.put("reasoningEnabled", reasoningEnabled);
        status.put("cacheOnly", cacheOnly);
        status.put("providerAvailable", chapterQuizService.isProviderAvailable());
        status.put("available", isQuizFeatureEnabled());
        status.put("generationAvailable", isQuizGenerationAvailable());
        status.put("queueDepth", chapterQuizService.getQueueDepth());
        status.put("queueProcessorRunning", chapterQuizService.isQueueProcessorRunning());
        status.put("difficultyRampEnabled", difficultyRampEnabled);
        status.put("difficultyRampChapterStep", difficultyRampChapterStep);
        status.put("difficultyRampMaxLevel", difficultyRampMaxLevel);
        status.put("metrics", quizMetricsService.snapshot());
        return status;
    }

    @GetMapping("/book/{bookId}/status")
    public Map<String, Object> getBookStatus(
            @PathVariable String bookId,
            HttpServletRequest request,
            HttpServletResponse response) {
        Map<String, Object> status = new HashMap<>();
        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        String readerId = identity.accountAuthenticated() ? null : identity.readerKey();
        status.put("bookId", bookId);
        status.put("enabled", quizEnabled);
        status.put("reasoningEnabled", reasoningEnabled);
        status.put("cacheOnly", cacheOnly);
        status.put("providerAvailable", chapterQuizService.isProviderAvailable());
        status.put("available", isQuizFeatureEnabled());
        status.put("generationAvailable", isQuizGenerationAvailable());
        status.put("queueDepth", chapterQuizService.getQueueDepth());
        status.put("queueProcessorRunning", chapterQuizService.isQueueProcessorRunning());
        status.put("difficultyRampEnabled", difficultyRampEnabled);
        status.put("difficultyRampChapterStep", difficultyRampChapterStep);
        status.put("difficultyRampMaxLevel", difficultyRampMaxLevel);
        status.put("trophiesUnlocked", quizProgressService.getBookTrophies(bookId, readerId, identity.userId()).size());
        status.put("metrics", quizMetricsService.snapshot());
        return status;
    }

    @GetMapping("/book/{bookId}/trophies")
    public ResponseEntity<List<QuizTrophy>> getBookTrophies(
            @PathVariable String bookId,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!isQuizFeatureEnabled()) {
            return ResponseEntity.status(403).build();
        }
        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(request, response);
        String readerId = identity.accountAuthenticated() ? null : identity.readerKey();
        return ResponseEntity.ok(quizProgressService.getBookTrophies(bookId, readerId, identity.userId()));
    }

    @GetMapping("/chapter/{chapterId}")
    public ResponseEntity<ChapterQuizResponse> getChapterQuiz(
            @PathVariable String chapterId,
            HttpServletRequest request) {
        String requestId = RequestCorrelation.resolveRequestId(request);
        if (!quizEnabled) {
            return ResponseEntity.status(403).build();
        }
        var bookId = chapterQuizService.findBookIdForChapter(chapterId).orElse(null);
        if (bookId == null) {
            log.warn("event=quiz_read_unknown_chapter requestId={} chapterId={}", requestId, chapterId);
            return ResponseEntity.notFound().build();
        }
        if (!isQuizFeatureEnabled()) {
            return ResponseEntity.status(403).build();
        }
        try {
            return chapterQuizService.getChapterQuiz(chapterId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            quizMetricsService.recordReadFailed();
            log.error(
                    "event=quiz_read_failed requestId={} endpoint={} chapterId={} bookId={} cacheOnly={} providerAvailable={} errorType={} errorMessage={}",
                    requestId,
                    "/api/quizzes/chapter/{chapterId}",
                    chapterId,
                    bookId,
                    cacheOnly,
                    chapterQuizService.isProviderAvailable(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e
            );
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/chapter/{chapterId}/status")
    public ResponseEntity<ChapterQuizStatusResponse> getChapterQuizStatus(
            @PathVariable String chapterId,
            HttpServletRequest request) {
        String requestId = RequestCorrelation.resolveRequestId(request);
        if (!quizEnabled) {
            return ResponseEntity.status(403).build();
        }
        var bookId = chapterQuizService.findBookIdForChapter(chapterId).orElse(null);
        if (bookId == null) {
            log.warn("event=quiz_status_unknown_chapter requestId={} chapterId={}", requestId, chapterId);
            return ResponseEntity.notFound().build();
        }
        if (!isQuizFeatureEnabled()) {
            return ResponseEntity.status(403).build();
        }
        try {
            return chapterQuizService.getChapterQuizStatus(chapterId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            quizMetricsService.recordStatusReadFailed();
            log.error(
                    "event=quiz_status_read_failed requestId={} endpoint={} chapterId={} bookId={} cacheOnly={} providerAvailable={} errorType={} errorMessage={}",
                    requestId,
                    "/api/quizzes/chapter/{chapterId}/status",
                    chapterId,
                    bookId,
                    cacheOnly,
                    chapterQuizService.isProviderAvailable(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e
            );
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/chapter/{chapterId}/generate")
    public ResponseEntity<Void> requestChapterQuizGeneration(@PathVariable String chapterId) {
        if (!isQuizGenerationConfigured()) {
            return ResponseEntity.status(403).build();
        }
        if (cacheOnly) {
            return ResponseEntity.status(409).build();
        }
        var bookId = chapterQuizService.findBookIdForChapter(chapterId).orElse(null);
        if (bookId == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            chapterQuizService.requestChapterQuiz(chapterId);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/chapter/{chapterId}/grade")
    public ResponseEntity<ChapterQuizGradeResponse> gradeQuiz(
            @PathVariable String chapterId,
            @RequestBody QuizSubmissionRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (!quizEnabled) {
            return ResponseEntity.status(403).build();
        }
        var bookId = chapterQuizService.findBookIdForChapter(chapterId).orElse(null);
        if (bookId == null) {
            return ResponseEntity.notFound().build();
        }
        if (!isQuizFeatureEnabled()) {
            return ResponseEntity.status(403).build();
        }
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }

        ReaderIdentityService.ReaderIdentity identity = readerIdentityService.resolve(httpRequest, httpResponse);
        String readerId = identity.accountAuthenticated() ? null : identity.readerKey();
        try {
            return chapterQuizService.gradeQuiz(
                            chapterId,
                            request.selectedOptionIndexes(),
                            readerId,
                            identity.userId()
                    )
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }

    private boolean isQuizFeatureEnabled() {
        return quizEnabled;
    }

    private boolean isQuizGenerationAvailable() {
        return isQuizGenerationConfigured() && !cacheOnly;
    }

    private boolean isQuizGenerationConfigured() {
        return quizEnabled && reasoningEnabled;
    }

    public record QuizSubmissionRequest(List<Integer> selectedOptionIndexes) {
    }
}
