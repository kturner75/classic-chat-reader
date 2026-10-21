package com.classicchatreader.repository;

import com.classicchatreader.entity.PasswordResetTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, String> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    boolean existsByUserIdAndCreatedAtAfter(String userId, LocalDateTime since);

    long deleteByUserId(String userId);

    long deleteByExpiresAtBefore(LocalDateTime now);
}
