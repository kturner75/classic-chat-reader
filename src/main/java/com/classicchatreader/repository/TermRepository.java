package com.classicchatreader.repository;

import com.classicchatreader.entity.TermEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TermRepository extends JpaRepository<TermEntity, String> {
    Optional<TermEntity> findByIdAndDeletedAtIsNull(String id);
    List<TermEntity> findByClassSectionIdAndDeletedAtIsNull(String classSectionId);
    Optional<TermEntity> findByClassSectionIdAndStatusAndDeletedAtIsNull(String classSectionId, String status);
    List<TermEntity> findByIdInAndStatusAndDeletedAtIsNull(List<String> ids, String status);
}
