package com.classicchatreader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "rate_limit_windows")
public class RateLimitWindowEntity {

    @Id
    @Column(name = "window_key", nullable = false, length = 255)
    private String windowKey;

    @Column(name = "scope_key", nullable = false, length = 255)
    private String scopeKey;

    @Column(name = "window_start_epoch", nullable = false)
    private long windowStartEpoch;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public String getWindowKey() {
        return windowKey;
    }

    public void setWindowKey(String windowKey) {
        this.windowKey = windowKey;
    }

    public String getScopeKey() {
        return scopeKey;
    }

    public void setScopeKey(String scopeKey) {
        this.scopeKey = scopeKey;
    }

    public long getWindowStartEpoch() {
        return windowStartEpoch;
    }

    public void setWindowStartEpoch(long windowStartEpoch) {
        this.windowStartEpoch = windowStartEpoch;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(int requestCount) {
        this.requestCount = requestCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
