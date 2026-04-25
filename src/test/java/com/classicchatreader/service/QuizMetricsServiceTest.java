package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuizMetricsServiceTest {

    @Test
    void snapshot_initialState_returnsZeroedMetrics() {
        QuizMetricsService metricsService = new QuizMetricsService();

        Map<String, Object> snapshot = metricsService.snapshot();

        assertEquals(0L, snapshot.get("generationRequested"));
        assertEquals(0L, snapshot.get("generationCompleted"));
        assertEquals(0L, snapshot.get("generationFallbackCompleted"));
        assertEquals(0L, snapshot.get("generationFailed"));
        assertEquals(0L, snapshot.get("generationAverageLatencyMs"));
        assertEquals(0L, snapshot.get("readFailed"));
        assertEquals(0L, snapshot.get("statusReadFailed"));
    }

    @Test
    void snapshot_afterGenerationActivity_reportsCountsAndLatency() {
        QuizMetricsService metricsService = new QuizMetricsService();

        metricsService.recordGenerationRequested();
        metricsService.recordGenerationRequested();
        metricsService.recordGenerationCompleted(false, 100);
        metricsService.recordGenerationCompleted(true, 300);
        metricsService.recordGenerationFailed(600);
        metricsService.recordReadFailed();
        metricsService.recordStatusReadFailed();

        Map<String, Object> snapshot = metricsService.snapshot();

        assertEquals(2L, snapshot.get("generationRequested"));
        assertEquals(2L, snapshot.get("generationCompleted"));
        assertEquals(1L, snapshot.get("generationFallbackCompleted"));
        assertEquals(1L, snapshot.get("generationFailed"));
        assertEquals(333L, snapshot.get("generationAverageLatencyMs"));
        assertEquals(1L, snapshot.get("readFailed"));
        assertEquals(1L, snapshot.get("statusReadFailed"));
    }
}
