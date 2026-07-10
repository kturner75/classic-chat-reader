package com.classicchatreader.repository;

import com.classicchatreader.entity.ReadingBuddyPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReadingBuddyPreferenceRepository extends JpaRepository<ReadingBuddyPreferenceEntity, String> {

    Optional<ReadingBuddyPreferenceEntity> findByOwnerKeyAndBookId(String ownerKey, String bookId);

    List<ReadingBuddyPreferenceEntity> findByOwnerKey(String ownerKey);
}
