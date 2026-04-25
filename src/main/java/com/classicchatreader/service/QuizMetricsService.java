package com.classicchatreader.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Service
public class QuizMetricsService {

    private final LongAdder generationRequested = new LongAdder();
    private final LongAdder generationCompleted = new LongAdder();
    private final LongAdder generationFallbackCompleted = new LongAdder();
    private final LongAdder generationFailed = new LongAdder();
    private final LongAdder readFailed = new LongAdder();
    private final LongAdder statusReadFailed = new LongAdder();
    private final AtomicLong generationLatencyTotalMs = new AtomicLong(0);

    public void recordGenerationRequested() {
        generationRequested.increment();
    }

    public void recordGenerationCompleted(boolean fallbackUsed, long durationMs) {
        generationCompleted.increment();
        if (fallbackUsed) {
            generationFallbackCompleted.increment();
        }
        if (durationMs > 0) {
            generationLatencyTotalMs.addAndGet(durationMs);
        }
    }

    public void recordGenerationFailed(long durationMs) {
        generationFailed.increment();
        if (durationMs > 0) {
            generationLatencyTotalMs.addAndGet(durationMs);
        }
    }

    public void recordReadFailed() {
        readFailed.increment();
    }

    public void recordStatusReadFailed() {
        statusReadFailed.increment();
    }

    public Map<String, Object> snapshot() {
        long completed = generationCompleted.sum();
        long failed = generationFailed.sum();
        long measured = completed + failed;
        long avgLatencyMs = measured == 0 ? 0 : generationLatencyTotalMs.get() / measured;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("generationRequested", generationRequested.sum());
        metrics.put("generationCompleted", completed);
        metrics.put("generationFallbackCompleted", generationFallbackCompleted.sum());
        metrics.put("generationFailed", failed);
        metrics.put("generationAverageLatencyMs", avgLatencyMs);
        metrics.put("readFailed", readFailed.sum());
        metrics.put("statusReadFailed", statusReadFailed.sum());
        return metrics;
    }
}
