package com.classicchatreader.config;

import com.classicchatreader.repository.RateLimitWindowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseRateLimiterPostgresTest {

    @Mock
    private RateLimitWindowRepository repository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void tryConsume_postgresUpsertPathHonorsLimitWithoutRepositoryReadWrite() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-14T12:00:00Z"), ZoneOffset.UTC);
        DatabaseRateLimiter limiter = new DatabaseRateLimiter(repository, 10_000, 3, clock, jdbcTemplate, true);

        doReturn(1, 1, 0)
                .when(jdbcTemplate)
                .update(anyString(), any(), any(), any(), any(), any(), any());

        assertTrue(limiter.tryConsume("GENERATION:ip:127.0.0.1", 2, Duration.ofSeconds(60)));
        assertTrue(limiter.tryConsume("GENERATION:ip:127.0.0.1", 2, Duration.ofSeconds(60)));
        assertFalse(limiter.tryConsume("GENERATION:ip:127.0.0.1", 2, Duration.ofSeconds(60)));

        verify(repository, never()).findById(anyString());
    }

    @Test
    void tryConsume_postgresUpsertPathRetriesOnTransientDataAccessFailure() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-14T12:00:00Z"), ZoneOffset.UTC);
        DatabaseRateLimiter limiter = new DatabaseRateLimiter(repository, 10_000, 3, clock, jdbcTemplate, true);

        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new TransientDataAccessResourceException("temporary"))
                .thenReturn(1);

        assertTrue(limiter.tryConsume("CHAT:ip:127.0.0.1", 5, Duration.ofSeconds(60)));
    }
}
