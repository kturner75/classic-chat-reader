package com.classicchatreader.repository;

import com.classicchatreader.entity.RateLimitWindowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface RateLimitWindowRepository extends JpaRepository<RateLimitWindowEntity, String> {

    @Modifying
    @Transactional
    @Query("delete from RateLimitWindowEntity w where w.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
