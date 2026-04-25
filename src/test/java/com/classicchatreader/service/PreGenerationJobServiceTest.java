package com.classicchatreader.service;

import com.classicchatreader.model.GenerationJobStatusResponse;
import com.classicchatreader.model.GenerationPipelineStatus;
import com.classicchatreader.service.PreGenerationJobService.JobState;
import com.classicchatreader.service.PreGenerationJobService.PreGenJobStatus;
import com.classicchatreader.service.PreGenerationService.PreGenResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreGenerationJobServiceTest {

    @Mock
    private PreGenerationService preGenerationService;
    @Mock
    private GenerationJobStatusService generationJobStatusService;
    @Mock
    private BookStorageService bookStorageService;

    private PreGenerationJobService preGenerationJobService;

    @BeforeEach
    void setUp() {
        preGenerationJobService = new PreGenerationJobService(
                preGenerationService,
                generationJobStatusService,
                bookStorageService,
                1
        );
    }

    @AfterEach
    void tearDown() {
        preGenerationJobService.shutdown();
    }

    @Test
    void startBookJob_returnsImmediatelyAndEventuallyCompletes() throws Exception {
        when(preGenerationService.preGenerateForBook("book-1")).thenReturn(success("book-1", "done"));
        when(generationJobStatusService.getBookStatus("book-1")).thenReturn(sampleProgress("book-1"));

        PreGenJobStatus started = preGenerationJobService.startBookJob("book-1");

        assertNotNull(started.jobId());
        assertFalse(started.jobId().isBlank());

        PreGenJobStatus completed = waitForTerminalState(started.jobId(), 3_000L);
        assertEquals(JobState.COMPLETED, completed.state());
        assertEquals("book-1", completed.bookId());
        assertNotNull(completed.result());
        assertTrue(completed.result().success());
        assertNotNull(completed.progress());
    }

    @Test
    void cancelJob_runningJob_marksCancelled() throws Exception {
        lenient().when(preGenerationService.preGenerateForBook("book-1")).thenAnswer(invocation -> {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success("book-1", "interrupted");
        });

        PreGenJobStatus started = preGenerationJobService.startBookJob("book-1");
        waitForRunning(started.jobId(), 2_000L);

        Optional<PreGenJobStatus> cancelled = preGenerationJobService.cancelJob(started.jobId());
        assertTrue(cancelled.isPresent());
        assertTrue(cancelled.get().cancelRequested());

        PreGenJobStatus terminal = waitForTerminalState(started.jobId(), 3_000L);
        assertEquals(JobState.CANCELLED, terminal.state());
    }

    @Test
    void getJobStatus_unknownJob_returnsEmpty() {
        assertTrue(preGenerationJobService.getJobStatus("missing-job").isEmpty());
    }

    private void waitForRunning(String jobId, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            PreGenJobStatus status = preGenerationJobService.getJobStatus(jobId).orElseThrow();
            if (status.state() == JobState.RUNNING
                    || status.state() == JobState.CANCELLING
                    || status.state() == JobState.CANCELLED
                    || status.state() == JobState.COMPLETED
                    || status.state() == JobState.FAILED) {
                return;
            }
            Thread.sleep(20L);
        }
        fail("Timed out waiting for job to start running");
    }

    private PreGenJobStatus waitForTerminalState(String jobId, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        PreGenJobStatus latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = preGenerationJobService.getJobStatus(jobId).orElseThrow();
            if (latest.state() == JobState.COMPLETED
                    || latest.state() == JobState.FAILED
                    || latest.state() == JobState.CANCELLED) {
                return latest;
            }
            Thread.sleep(20L);
        }
        fail("Timed out waiting for job to complete, last state=" + (latest != null ? latest.state() : "none"));
        return latest;
    }

    private PreGenResult success(String bookId, String message) {
        return new PreGenResult(
                true,
                bookId,
                "Book Title",
                message,
                1,
                1,
                0,
                1,
                0,
                1,
                0,
                1,
                1,
                1
        );
    }

    private GenerationJobStatusResponse sampleProgress(String bookId) {
        GenerationPipelineStatus status = GenerationPipelineStatus.of(1, 0, 1, 5, 0);
        return new GenerationJobStatusResponse(
                "book",
                bookId,
                LocalDateTime.now(),
                status,
                status,
                status,
                status,
                status
        );
    }
}
