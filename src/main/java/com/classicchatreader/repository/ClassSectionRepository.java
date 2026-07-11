package com.classicchatreader.repository;

import com.classicchatreader.entity.ClassSectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassSectionRepository extends JpaRepository<ClassSectionEntity, String> {
    Optional<ClassSectionEntity> findByIdAndDeletedAtIsNull(String id);
    List<ClassSectionEntity> findByOwnerUserIdAndDeletedAtIsNull(String ownerUserId);
}
