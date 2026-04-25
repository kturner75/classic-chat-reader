package com.classicchatreader.config;

import com.classicchatreader.repository.RateLimitWindowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class DatabaseRateLimiterTest {

    @Autowired
    private RateLimitWindowRepository repository;

    @Test
    void tryConsume_enforcesLimitPerWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-14T12:00:00Z"));
        DatabaseRateLimiter limiter = new DatabaseRateLimiter(repository, 1, 3, clock);

        Duration window = Duration.ofSeconds(60);
        assertTrue(limiter.tryConsume("GENERATION:session-a", 2, window));
        assertTrue(limiter.tryConsume("GENERATION:session-a", 2, window));
        assertFalse(limiter.tryConsume("GENERATION:session-a", 2, window));
    }

    @Test
    void tryConsume_scopesCountsPerKey() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-14T12:00:00Z"));
        DatabaseRateLimiter limiter = new DatabaseRateLimiter(repository, 1, 3, clock);

        Duration window = Duration.ofSeconds(60);
        assertTrue(limiter.tryConsume("GENERATION:session-a", 1, window));
        assertTrue(limiter.tryConsume("GENERATION:session-b", 1, window));
        assertFalse(limiter.tryConsume("GENERATION:session-a", 1, window));
    }

    @Test
    void tryConsume_allowsRequestInNextWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-14T12:00:00Z"));
        DatabaseRateLimiter limiter = new DatabaseRateLimiter(repository, 1, 3, clock);

        Duration window = Duration.ofSeconds(60);
        assertTrue(limiter.tryConsume("CHAT:session-a", 1, window));
        assertFalse(limiter.tryConsume("CHAT:session-a", 1, window));

        clock.advanceSeconds(61);
        assertTrue(limiter.tryConsume("CHAT:session-a", 1, window));
    }

    @Test
    void tryConsume_cleansExpiredRows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-14T12:00:00Z"));
        DatabaseRateLimiter limiter = new DatabaseRateLimiter(repository, 1, 3, clock);

        Duration window = Duration.ofSeconds(60);
        assertTrue(limiter.tryConsume("GENERATION:session-a", 1, window));
        assertEquals(1, repository.count());

        clock.advanceSeconds(181);
        assertTrue(limiter.tryConsume("GENERATION:session-a", 1, window));
        assertEquals(1, repository.count());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }
    }
}
