package com.classicchatreader.repository;

import com.classicchatreader.entity.PendingExternalIdentityLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PendingExternalIdentityLinkRepository extends JpaRepository<PendingExternalIdentityLinkEntity, String> {

    Optional<PendingExternalIdentityLinkEntity> findByTokenHash(String tokenHash);

    long deleteByTokenHash(String tokenHash);

    long deleteByUserId(String userId);

    long deleteByExpiresAtBefore(LocalDateTime now);
}
