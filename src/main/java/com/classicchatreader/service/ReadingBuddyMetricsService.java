package com.classicchatreader.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process counters for Reading Buddy (mirrors {@link RecapMetricsService}).
 * Chat counters are used in PR 3b; check/summary counters reserved for later PRs.
 */
@Service
public class ReadingBuddyMetricsService {

    private final LongAdder chatTotal = new LongAdder();
    private final LongAdder chatFailed = new LongAdder();
    private final LongAdder chatRejected = new LongAdder();
    private final AtomicLong chatLatencyTotalMs = new AtomicLong(0);

    private final LongAdder checkTotal = new LongAdder();
    private final LongAdder checkSilence = new LongAdder();
    private final LongAdder checkComment = new LongAdder();
    private final LongAdder checkFailed = new LongAdder();
    private final AtomicLong checkLatencyTotalMs = new AtomicLong(0);

    private final LongAdder summaryRefreshTotal = new LongAdder();
    private final LongAdder summaryRefreshFailed = new LongAdder();
    private final LongAdder claimMergedTotal = new LongAdder();

    public void recordChatRequest() {
        chatTotal.increment();
    }

    public void recordChatFailed() {
        chatFailed.increment();
    }

    public void recordChatRejected() {
        chatRejected.increment();
    }

    public void recordChatLatency(long durationMs) {
        if (durationMs > 0) {
            chatLatencyTotalMs.addAndGet(durationMs);
        }
    }

    public void recordCheckTotal() {
        checkTotal.increment();
    }

    public void recordCheckSilence() {
        checkSilence.increment();
    }

    public void recordCheckComment() {
        checkComment.increment();
    }

    public void recordCheckFailed() {
        checkFailed.increment();
    }

    public void recordCheckLatency(long durationMs) {
        if (durationMs > 0) {
            checkLatencyTotalMs.addAndGet(durationMs);
        }
    }

    public void recordSummaryRefresh() {
        summaryRefreshTotal.increment();
    }

    public void recordSummaryRefreshFailed() {
        summaryRefreshFailed.increment();
    }

    public void recordClaimMerged() {
        claimMergedTotal.increment();
    }

    public Map<String, Object> snapshot() {
        long chats = chatTotal.sum();
        long checks = checkTotal.sum();
        long avgChatLatencyMs = chats == 0 ? 0 : chatLatencyTotalMs.get() / chats;
        long avgCheckLatencyMs = checks == 0 ? 0 : checkLatencyTotalMs.get() / checks;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("chatTotal", chats);
        metrics.put("chatFailed", chatFailed.sum());
        metrics.put("chatRejected", chatRejected.sum());
        metrics.put("chatLatencyTotalMs", chatLatencyTotalMs.get());
        metrics.put("chatAverageLatencyMs", avgChatLatencyMs);
        metrics.put("checkTotal", checks);
        metrics.put("checkSilence", checkSilence.sum());
        metrics.put("checkComment", checkComment.sum());
        metrics.put("checkFailed", checkFailed.sum());
        metrics.put("checkLatencyTotalMs", checkLatencyTotalMs.get());
        metrics.put("checkAverageLatencyMs", avgCheckLatencyMs);
        metrics.put("summaryRefreshTotal", summaryRefreshTotal.sum());
        metrics.put("summaryRefreshFailed", summaryRefreshFailed.sum());
        metrics.put("claimMergedTotal", claimMergedTotal.sum());
        return metrics;
    }
}
