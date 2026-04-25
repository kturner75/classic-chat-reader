package com.classicchatreader.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple fixed-window in-memory rate limiter keyed by endpoint category + client IP.
 */
@Component
@ConditionalOnProperty(name = "security.public.rate-limit.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryIpRateLimiter implements PublicApiRateLimiter {

    private final int maxKeys;
    private final AtomicInteger cleanupTicker = new AtomicInteger();
    private final ConcurrentHashMap<String, CounterWindow> windows = new ConcurrentHashMap<>();

    public InMemoryIpRateLimiter(@Value("${security.public.rate-limit.max-keys:20000}") int maxKeys) {
        this.maxKeys = Math.max(1000, maxKeys);
    }

    @Override
    public boolean tryConsume(String key, int maxRequests, Duration window) {
        if (key == null || key.isBlank() || maxRequests <= 0 || window == null || window.isZero() || window.isNegative()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();

        CounterWindow counter = windows.computeIfAbsent(key, ignored -> new CounterWindow(now));
        boolean allowed = counter.tryConsume(now, windowMillis, maxRequests);

        int tick = cleanupTicker.incrementAndGet();
        if ((tick & 0xFF) == 0 || windows.size() > maxKeys) {
            cleanup(now, windowMillis * 3);
        }

        return allowed;
    }

    private void cleanup(long now, long staleThresholdMillis) {
        for (Map.Entry<String, CounterWindow> entry : windows.entrySet()) {
            if (now - entry.getValue().getLastSeenMillis() > staleThresholdMillis) {
                windows.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private static final class CounterWindow {
        private long windowStartMillis;
        private int count;
        private long lastSeenMillis;

        private CounterWindow(long now) {
            this.windowStartMillis = now;
            this.lastSeenMillis = now;
        }

        private synchronized boolean tryConsume(long now, long windowMillis, int limit) {
            if (now - windowStartMillis >= windowMillis) {
                windowStartMillis = now;
                count = 0;
            }

            lastSeenMillis = now;
            if (count >= limit) {
                return false;
            }

            count++;
            return true;
        }

        private synchronized long getLastSeenMillis() {
            return lastSeenMillis;
        }
    }
}
