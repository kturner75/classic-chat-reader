package com.classicchatreader.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImagineInFlightLimiterTest {

    @Test
    void defaultsToFourAndClampsZeroToOne() {
        assertThat(new ImagineInFlightLimiter(4, 30).getMaxInFlight()).isEqualTo(4);
        assertThat(new ImagineInFlightLimiter(0, 30).getMaxInFlight()).isEqualTo(1);
        assertThat(ImagineInFlightLimiter.DEFAULT_MAX_IN_FLIGHT).isEqualTo(4);
    }

    @Test
    void sharedCapBlocksTheFifthCallerUntilAPermitFrees() throws Exception {
        ImagineInFlightLimiter limiter = new ImagineInFlightLimiter(4, 30);
        CountDownLatch fourStarted = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            for (int i = 0; i < 5; i++) {
                pool.submit(() -> limiter.run(() -> {
                    started.incrementAndGet();
                    fourStarted.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release timed out");
                    }
                    return null;
                }));
            }

            assertThat(fourStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);
            assertThat(started.get()).isEqualTo(4);
            assertThat(limiter.availablePermits()).isEqualTo(0);

            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(started.get()).isEqualTo(5);
            assertThat(limiter.availablePermits()).isEqualTo(4);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rateLimitCooldownBlocksTheNextCallAndDoesNotShrink() throws Exception {
        ImagineInFlightLimiter limiter = new ImagineInFlightLimiter(2, 5);
        limiter.noteRateLimited(rateLimited("2"));
        long afterFirst = limiter.remainingCooldownMillis();
        assertThat(afterFirst).isGreaterThanOrEqualTo(1500L);

        limiter.noteRateLimited(rateLimited("1"));
        assertThat(limiter.remainingCooldownMillis()).isGreaterThanOrEqualTo(afterFirst - 200L);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> limiter.run(() -> {
            throw rateLimited("1");
        })).isInstanceOf(WebClientResponseException.class);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(waitedMs).isGreaterThanOrEqualTo(1400L);
        assertThat(limiter.remainingCooldownMillis()).isGreaterThanOrEqualTo(800L);
    }

    @Test
    void rateLimitedRunNotesCooldownFromRetryAfter() {
        ImagineInFlightLimiter limiter = new ImagineInFlightLimiter(1, 30);
        assertThatThrownBy(() -> limiter.run(() -> {
            throw rateLimited("3");
        })).isInstanceOf(WebClientResponseException.class);
        assertThat(limiter.remainingCooldownMillis()).isGreaterThanOrEqualTo(2500L);
        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    private static WebClientResponseException rateLimited(String retryAfterSeconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        return WebClientResponseException.create(
                429,
                "Too Many Requests",
                headers,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }
}
