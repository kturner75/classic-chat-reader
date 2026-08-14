package com.classicchatreader.repository;

import com.classicchatreader.entity.AssignmentQuizEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AssignmentQuizRepository extends JpaRepository<AssignmentQuizEntity, String> {
    Optional<AssignmentQuizEntity> findByAssignmentId(String assignmentId);

    boolean existsByAssignmentId(String assignmentId);
}
