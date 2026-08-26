package com.classicchatreader.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.ZonedDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Shared cap on Grok Imagine (and other {@code /images/generations}) HTTP calls.
 *
 * <p>Portraits, illustrations, and covers all go through {@link ImageGenerationHttpClient},
 * so one limiter is the process-wide in-flight budget. Lease claims stay one job per
 * chapter/character; this only limits provider HTTP.
 *
 * <p>429 responses install a cooldown. Later callers wait rather than immediately filling
 * a freed permit, so parallelism cannot make backoff more aggressive than serial.
 */
@Component
public class ImagineInFlightLimiter {

    private static final Logger log = LoggerFactory.getLogger(ImagineInFlightLimiter.class);
    public static final int DEFAULT_MAX_IN_FLIGHT = 4;

    private final Semaphore permits;
    private final int maxInFlight;
    private final long defaultCooldownMs;
    private final Object cooldownLock = new Object();
    private long cooldownUntilMs;

    public ImagineInFlightLimiter(
            @Value("${generation.imagine.max-in-flight:4}") int maxInFlight,
            @Value("${generation.retry.initial-delay-seconds:30}") int initialRetryDelaySeconds) {
        this.maxInFlight = Math.max(1, maxInFlight);
        this.defaultCooldownMs = Math.max(1, initialRetryDelaySeconds) * 1000L;
        this.permits = new Semaphore(this.maxInFlight, true);
    }

    public int getMaxInFlight() {
        return maxInFlight;
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public long remainingCooldownMillis() {
        synchronized (cooldownLock) {
            return remainingCooldownMillisLocked();
        }
    }

    public <T> T run(Callable<T> task) throws Exception {
        acquire();
        try {
            return task.call();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                noteRateLimited(e);
            }
            throw e;
        } finally {
            permits.release();
        }
    }

    void acquire() throws InterruptedException {
        while (true) {
            waitForCooldown();
            permits.acquire();
            if (remainingCooldownMillis() > 0L) {
                permits.release();
                continue;
            }
            return;
        }
    }

    void noteRateLimited(WebClientResponseException exception) {
        long waitMs = parseRetryAfterMillis(exception);
        long until = System.currentTimeMillis() + waitMs;
        synchronized (cooldownLock) {
            if (until > cooldownUntilMs) {
                cooldownUntilMs = until;
                log.warn(
                        "event=imagine_rate_limited cooldown_ms={} in_flight_cap={}",
                        waitMs,
                        maxInFlight
                );
            }
        }
    }

    private void waitForCooldown() throws InterruptedException {
        synchronized (cooldownLock) {
            long remaining = remainingCooldownMillisLocked();
            while (remaining > 0L) {
                cooldownLock.wait(remaining);
                remaining = remainingCooldownMillisLocked();
            }
        }
    }

    private long remainingCooldownMillisLocked() {
        return Math.max(0L, cooldownUntilMs - System.currentTimeMillis());
    }

    /**
     * RFC 7231 {@code Retry-After}: {@code delay-seconds} or {@code HTTP-date}.
     * Returns a delay from now. A past HTTP-date is 0 (do not shorten an existing
     * cooldown). Unparsable values use the configured default floor.
     */
    private long parseRetryAfterMillis(WebClientResponseException exception) {
        String retryAfter = exception.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return defaultCooldownMs;
        }
        String trimmed = retryAfter.trim();
        Long deltaMs = parseDelaySecondsMillis(trimmed);
        if (deltaMs != null) {
            return deltaMs;
        }
        try {
            ZonedDateTime retryAt = exception.getHeaders().getFirstZonedDateTime(HttpHeaders.RETRY_AFTER);
            if (retryAt != null) {
                return Math.max(0L, retryAt.toInstant().toEpochMilli() - System.currentTimeMillis());
            }
        } catch (IllegalArgumentException ignored) {
            // Not an IMF-fixdate / obs-date HTTP-date.
        }
        return defaultCooldownMs;
    }

    private static Long parseDelaySecondsMillis(String retryAfter) {
        if (retryAfter.isEmpty() || !retryAfter.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Math.max(1L, Long.parseLong(retryAfter) * 1000L);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }
}
