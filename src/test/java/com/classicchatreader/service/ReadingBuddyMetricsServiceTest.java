package com.classicchatreader.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadingBuddyMetricsServiceTest {

    @Test
    void snapshot_tracksChatCountersAndLatency() {
        ReadingBuddyMetricsService metrics = new ReadingBuddyMetricsService();
        metrics.recordChatRequest();
        metrics.recordChatRequest();
        metrics.recordChatFailed();
        metrics.recordChatRejected();
        metrics.recordChatLatency(100);
        metrics.recordChatLatency(300);

        Map<String, Object> snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("chatTotal"));
        assertEquals(1L, snapshot.get("chatFailed"));
        assertEquals(1L, snapshot.get("chatRejected"));
        assertEquals(400L, snapshot.get("chatLatencyTotalMs"));
        assertEquals(200L, snapshot.get("chatAverageLatencyMs"));
    }

    @Test
    void snapshot_tracksSummaryRefreshCounters() {
        ReadingBuddyMetricsService metrics = new ReadingBuddyMetricsService();
        metrics.recordSummaryRefresh();
        metrics.recordSummaryRefresh();
        metrics.recordSummaryRefreshFailed();

        Map<String, Object> snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("summaryRefreshTotal"));
        assertEquals(1L, snapshot.get("summaryRefreshFailed"));
    }
}
