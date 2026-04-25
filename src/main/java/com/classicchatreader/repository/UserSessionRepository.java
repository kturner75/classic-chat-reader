package com.classicchatreader.repository;

import com.classicchatreader.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSessionEntity, String> {

    Optional<UserSessionEntity> findByTokenHash(String tokenHash);

    long deleteByExpiresAtBefore(LocalDateTime now);

    long deleteByTokenHash(String tokenHash);
}
