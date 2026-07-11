package com.classicchatreader.repository;

import com.classicchatreader.entity.InviteLinkEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InviteLinkRepository extends JpaRepository<InviteLinkEntity, String> {
    Optional<InviteLinkEntity> findByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InviteLinkEntity i WHERE i.codeHash = :codeHash")
    Optional<InviteLinkEntity> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    List<InviteLinkEntity> findByTermIdAndRevokedAtIsNullOrderByCreatedAtDesc(String termId);
}
