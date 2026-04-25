package com.classicchatreader.config;

import jakarta.persistence.OptimisticLockException;
import com.classicchatreader.entity.RateLimitWindowEntity;
import com.classicchatreader.repository.RateLimitWindowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.lang.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "security.public.rate-limit.store", havingValue = "database")
public class DatabaseRateLimiter implements PublicApiRateLimiter {

    private static final String POSTGRES_TRY_CONSUME_SQL = """
            INSERT INTO rate_limit_windows (
                window_key,
                scope_key,
                window_start_epoch,
                request_count,
                updated_at,
                expires_at,
                version
            )
            VALUES (?, ?, ?, 1, ?, ?, 0)
            ON CONFLICT (window_key)
            DO UPDATE SET
                request_count = rate_limit_windows.request_count + 1,
                updated_at = EXCLUDED.updated_at,
                expires_at = EXCLUDED.expires_at,
                version = rate_limit_windows.version + 1
            WHERE rate_limit_windows.request_count < ?
            """;

    private final RateLimitWindowRepository repository;
    @Nullable
    private final JdbcTemplate jdbcTemplate;
    private final boolean postgresOptimizedPath;
    private final AtomicInteger cleanupTicker = new AtomicInteger();
    private final int cleanupInterval;
    private final int maxRetries;
    private final Clock clock;

    @Autowired
    public DatabaseRateLimiter(
            RateLimitWindowRepository repository,
            @Value("${security.public.rate-limit.cleanup-interval:256}") int cleanupInterval,
            @Value("${security.public.rate-limit.db-max-retries:4}") int maxRetries,
            DataSource dataSource) {
        this(
                repository,
                cleanupInterval,
                maxRetries,
                Clock.systemUTC(),
                new JdbcTemplate(dataSource),
                detectPostgres(dataSource)
        );
    }

    DatabaseRateLimiter(
            RateLimitWindowRepository repository,
            int cleanupInterval,
            int maxRetries,
            Clock clock) {
        this(repository, cleanupInterval, maxRetries, clock, null, false);
    }

    DatabaseRateLimiter(
            RateLimitWindowRepository repository,
            int cleanupInterval,
            int maxRetries,
            Clock clock,
            @Nullable JdbcTemplate jdbcTemplate,
            boolean postgresOptimizedPath) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.postgresOptimizedPath = postgresOptimizedPath;
        this.cleanupInterval = Math.max(1, cleanupInterval);
        this.maxRetries = Math.max(1, maxRetries);
        this.clock = clock;
    }

    @Override
    public boolean tryConsume(String key, int maxRequests, Duration window) {
        if (key == null || key.isBlank() || maxRequests <= 0 || window == null || window.isZero() || window.isNegative()) {
            return false;
        }

        long windowMillis = Math.max(1, window.toMillis());
        long nowMillis = clock.millis();
        long bucketStart = (nowMillis / windowMillis) * windowMillis;
        String bucketKey = buildBucketKey(key, bucketStart);
        Instant now = Instant.ofEpochMilli(nowMillis);
        Instant expiresAt = Instant.ofEpochMilli(bucketStart).plus(window.multipliedBy(3));

        maybeCleanup(now);

        if (postgresOptimizedPath && jdbcTemplate != null) {
            return tryConsumeWithPostgresUpsert(bucketKey, key, bucketStart, now, expiresAt, maxRequests);
        }

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Optional<RateLimitWindowEntity> existing = repository.findById(bucketKey);
                if (existing.isEmpty()) {
                    RateLimitWindowEntity newWindow = new RateLimitWindowEntity();
                    newWindow.setWindowKey(bucketKey);
                    newWindow.setScopeKey(key);
                    newWindow.setWindowStartEpoch(bucketStart);
                    newWindow.setRequestCount(1);
                    newWindow.setUpdatedAt(now);
                    newWindow.setExpiresAt(expiresAt);
                    repository.saveAndFlush(newWindow);
                    return true;
                }

                RateLimitWindowEntity windowEntity = existing.get();
                if (windowEntity.getRequestCount() >= maxRequests) {
                    return false;
                }

                windowEntity.setRequestCount(windowEntity.getRequestCount() + 1);
                windowEntity.setUpdatedAt(now);
                windowEntity.setExpiresAt(expiresAt);
                repository.saveAndFlush(windowEntity);
                return true;
            } catch (DataIntegrityViolationException
                     | ObjectOptimisticLockingFailureException
                     | OptimisticLockException e) {
                if (attempt == maxRetries) {
                    return false;
                }
            }
        }

        return false;
    }

    private boolean tryConsumeWithPostgresUpsert(
            String bucketKey,
            String scopeKey,
            long bucketStart,
            Instant now,
            Instant expiresAt,
            int maxRequests) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                int updated = jdbcTemplate.update(
                        POSTGRES_TRY_CONSUME_SQL,
                        bucketKey,
                        scopeKey,
                        bucketStart,
                        Timestamp.from(now),
                        Timestamp.from(expiresAt),
                        maxRequests
                );
                return updated > 0;
            } catch (DataAccessException e) {
                if (attempt == maxRetries) {
                    return false;
                }
            }
        }
        return false;
    }

    private String buildBucketKey(String scopeKey, long bucketStartMillis) {
        String normalizedScope = scopeKey.length() > 220 ? scopeKey.substring(0, 220) : scopeKey;
        return normalizedScope + ":" + bucketStartMillis;
    }

    private void maybeCleanup(Instant now) {
        int tick = cleanupTicker.incrementAndGet();
        if (tick % cleanupInterval != 0) {
            return;
        }
        repository.deleteExpired(now);
    }

    private static boolean detectPostgres(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase(Locale.ROOT).contains("postgresql");
        } catch (SQLException e) {
            return false;
        }
    }
}
