package com.classicchatreader.repository;

import com.classicchatreader.entity.ReadingBuddyMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReadingBuddyMessageRepository extends JpaRepository<ReadingBuddyMessageEntity, String> {

    List<ReadingBuddyMessageEntity> findByOwnerKey(String ownerKey);

    List<ReadingBuddyMessageEntity> findByOwnerKeyAndBookIdAndPersonaIdOrderByCreatedAtAsc(
            String ownerKey,
            String bookId,
            String personaId
    );

    long countByOwnerKeyAndBookIdAndPersonaId(String ownerKey, String bookId, String personaId);

    boolean existsByOwnerKeyAndBookIdAndPersonaIdAndContentHash(
            String ownerKey,
            String bookId,
            String personaId,
            String contentHash
    );

    Optional<ReadingBuddyMessageEntity> findByOwnerKeyAndBookIdAndPersonaIdAndProactivePositionKey(
            String ownerKey,
            String bookId,
            String personaId,
            String proactivePositionKey
    );

    void deleteByOwnerKeyAndBookIdAndPersonaId(String ownerKey, String bookId, String personaId);
}
