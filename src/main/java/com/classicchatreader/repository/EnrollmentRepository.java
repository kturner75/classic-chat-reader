package com.classicchatreader.repository;

import com.classicchatreader.entity.EnrollmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentRepository extends JpaRepository<EnrollmentEntity, String> {
    Optional<EnrollmentEntity> findByTermIdAndUserIdAndDeletedAtIsNull(String termId, String userId);
    List<EnrollmentEntity> findByUserIdAndStatusAndDeletedAtIsNull(String userId, String status);
    List<EnrollmentEntity> findByTermIdAndStatusAndDeletedAtIsNullOrderByJoinedDateAsc(String termId, String status);
}
