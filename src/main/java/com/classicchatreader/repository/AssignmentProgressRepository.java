package com.classicchatreader.repository;

import com.classicchatreader.entity.AssignmentProgressEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssignmentProgressRepository extends JpaRepository<AssignmentProgressEntity, String> {

    Optional<AssignmentProgressEntity> findByAssignmentIdAndUserId(String assignmentId, String userId);

    List<AssignmentProgressEntity> findByTermIdAndUserId(String termId, String userId);
}
