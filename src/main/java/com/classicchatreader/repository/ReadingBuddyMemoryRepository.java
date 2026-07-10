package com.classicchatreader.repository;

import com.classicchatreader.entity.ReadingBuddyMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReadingBuddyMemoryRepository extends JpaRepository<ReadingBuddyMemoryEntity, String> {

    List<ReadingBuddyMemoryEntity> findByOwnerKey(String ownerKey);

    Optional<ReadingBuddyMemoryEntity> findByOwnerKeyAndBookIdAndPersonaId(
            String ownerKey,
            String bookId,
            String personaId
    );

    void deleteByOwnerKeyAndBookIdAndPersonaId(String ownerKey, String bookId, String personaId);
}
