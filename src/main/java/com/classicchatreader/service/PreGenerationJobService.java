package com.classicchatreader.service;

import com.classicchatreader.model.GenerationJobStatusResponse;
import com.classicchatreader.model.Book;
import com.classicchatreader.service.PreGenerationService.PreGenResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public class PreGenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(PreGenerationJobService.class);

    public enum JobType {
        BOOK,
        GUTENBERG
    }

    public enum JobState {
        QUEUED,
        RUNNING,
        CANCELLING,
        CANCELLED,
        COMPLETED,
        FAILED
    }

    public record PreGenJobStatus(
            String jobId,
            JobType jobType,
            JobState state,
            String bookId,
            Integer gutenbergId,
            boolean cancelRequested,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            String message,
            String error,
            PreGenResult result,
            GenerationJobStatusResponse progress
    ) {
    }

    private final PreGenerationService preGenerationService;
    private final GenerationJobStatusService generationJobStatusService;
    private final BookStorageService bookStorageService;
    private final ConcurrentHashMap<String, PreGenerationJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executorService;

    public PreGenerationJobService(
            PreGenerationService preGenerationService,
            GenerationJobStatusService generationJobStatusService,
            BookStorageService bookStorageService,
            @Value("${pregen.jobs.max-concurrent:2}") int maxConcurrentJobs) {
        this.preGenerationService = preGenerationService;
        this.generationJobStatusService = generationJobStatusService;
        this.bookStorageService = bookStorageService;
        this.executorService = Executors.newFixedThreadPool(
                Math.max(1, maxConcurrentJobs),
                new PregenJobThreadFactory());
    }

    public PreGenJobStatus startBookJob(String bookId) {
        PreGenerationJob job = new PreGenerationJob(
                UUID.randomUUID().toString(),
                JobType.BOOK,
                bookId,
                null
        );
        jobs.put(job.jobId, job);
        submitJob(job, () -> preGenerationService.preGenerateForBook(bookId));
        return toStatus(job);
    }

    public PreGenJobStatus startGutenbergJob(int gutenbergId) {
        PreGenerationJob job = new PreGenerationJob(
                UUID.randomUUID().toString(),
                JobType.GUTENBERG,
                null,
                gutenbergId
        );
        jobs.put(job.jobId, job);
        submitJob(job, () -> preGenerationService.preGenerateByGutenbergId(gutenbergId));
        return toStatus(job);
    }

    public Optional<PreGenJobStatus> getJobStatus(String jobId) {
        PreGenerationJob job = jobs.get(jobId);
        if (job == null) {
            return Optional.empty();
        }
        return Optional.of(toStatus(job));
    }

    public Optional<PreGenJobStatus> cancelJob(String jobId) {
        PreGenerationJob job = jobs.get(jobId);
        if (job == null) {
            return Optional.empty();
        }

        job.cancelRequested.set(true);
        JobState currentState = job.state;
        if (isTerminalState(currentState)) {
            return Optional.of(toStatus(job));
        }

        job.state = JobState.CANCELLING;
        job.message = "Cancellation requested";

        Future<?> future = job.future;
        boolean cancelled = future != null && future.cancel(true);
        if (cancelled || currentState == JobState.QUEUED) {
            markCancelled(job, "Job cancelled");
        }

        return Optional.of(toStatus(job));
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private void submitJob(PreGenerationJob job, Supplier<PreGenResult> work) {
        Future<?> future = executorService.submit(() -> runJob(job, work));
        job.future = future;
        if (job.cancelRequested.get()) {
            future.cancel(true);
        }
    }

    private void runJob(PreGenerationJob job, Supplier<PreGenResult> work) {
        if (job.cancelRequested.get()) {
            markCancelled(job, "Job cancelled before execution");
            return;
        }

        job.state = JobState.RUNNING;
        job.startedAt = LocalDateTime.now();
        job.message = "Job running";

        try {
            PreGenResult result = work.get();
            job.result = result;
            if (result != null && result.bookId() != null && !result.bookId().isBlank()) {
                job.bookId = result.bookId();
            }

            if (job.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                markCancelled(job, "Job cancelled");
                return;
            }

            if (result == null) {
                markFailed(job, "Pre-generation job produced no result");
                return;
            }

            if (result.success()) {
                job.state = JobState.COMPLETED;
                job.completedAt = LocalDateTime.now();
                job.message = firstNonBlank(result.message(), "Pre-generation completed");
                return;
            }

            markFailed(job, firstNonBlank(result.message(), "Pre-generation failed"));
        } catch (Exception ex) {
            if (job.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                markCancelled(job, "Job cancelled");
            } else {
                markFailed(job, safeErrorMessage(ex));
            }
        }
    }

    private void markCancelled(PreGenerationJob job, String message) {
        if (isTerminalState(job.state)) {
            return;
        }
        job.state = JobState.CANCELLED;
        job.completedAt = LocalDateTime.now();
        job.message = message;
    }

    private void markFailed(PreGenerationJob job, String errorMessage) {
        job.state = JobState.FAILED;
        job.completedAt = LocalDateTime.now();
        job.error = firstNonBlank(errorMessage, "Pre-generation failed");
        job.message = "Pre-generation failed";
    }

    private boolean isTerminalState(JobState state) {
        return state == JobState.COMPLETED || state == JobState.FAILED || state == JobState.CANCELLED;
    }

    private String safeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    private String firstNonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private PreGenJobStatus toStatus(PreGenerationJob job) {
        resolveBookId(job);
        GenerationJobStatusResponse progress = null;
        if (job.bookId != null && !job.bookId.isBlank()) {
            try {
                progress = generationJobStatusService.getBookStatus(job.bookId);
            } catch (Exception ex) {
                log.debug("Unable to load progress for pre-generation job {} ({})", job.jobId, job.bookId, ex);
            }
        }
        return new PreGenJobStatus(
                job.jobId,
                job.jobType,
                job.state,
                job.bookId,
                job.gutenbergId,
                job.cancelRequested.get(),
                job.createdAt,
                job.startedAt,
                job.completedAt,
                job.message,
                job.error,
                job.result,
                progress
        );
    }

    private void resolveBookId(PreGenerationJob job) {
        if (job.bookId != null || job.gutenbergId == null) {
            return;
        }
        Optional<Book> existingBook = bookStorageService.findBySource("gutenberg", String.valueOf(job.gutenbergId));
        existingBook.ifPresent(book -> job.bookId = book.id());
    }

    private static final class PreGenerationJob {
        private final String jobId;
        private final JobType jobType;
        private final Integer gutenbergId;
        private final LocalDateTime createdAt;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

        private volatile String bookId;
        private volatile JobState state;
        private volatile LocalDateTime startedAt;
        private volatile LocalDateTime completedAt;
        private volatile String message;
        private volatile String error;
        private volatile PreGenResult result;
        private volatile Future<?> future;

        private PreGenerationJob(String jobId, JobType jobType, String bookId, Integer gutenbergId) {
            this.jobId = jobId;
            this.jobType = jobType;
            this.bookId = bookId;
            this.gutenbergId = gutenbergId;
            this.createdAt = LocalDateTime.now();
            this.state = JobState.QUEUED;
            this.message = "Job queued";
        }
    }

    private static final class PregenJobThreadFactory implements ThreadFactory {
        private final AtomicInteger nextThreadId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "pregen-job-" + nextThreadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
