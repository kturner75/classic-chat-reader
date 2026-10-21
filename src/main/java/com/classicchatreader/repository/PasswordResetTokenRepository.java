package com.classicchatreader.repository;

import com.classicchatreader.entity.PasswordResetTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, String> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    /** Deletes the row for this hash and returns how many went: 1 means this caller owns the reset. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PasswordResetTokenEntity t WHERE t.tokenHash = :tokenHash")
    int claimByTokenHash(@Param("tokenHash") String tokenHash);

    boolean existsByUserIdAndCreatedAtAfter(String userId, LocalDateTime since);

    long deleteByUserId(String userId);

    long deleteByExpiresAtBefore(LocalDateTime now);
}
