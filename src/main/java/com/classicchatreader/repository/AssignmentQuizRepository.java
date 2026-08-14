package com.classicchatreader.repository;

import com.classicchatreader.entity.AssignmentQuizEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.Optional;

@Repository
public interface AssignmentQuizRepository extends JpaRepository<AssignmentQuizEntity, String> {
    Optional<AssignmentQuizEntity> findByAssignmentId(String assignmentId);

    boolean existsByAssignmentId(String assignmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM AssignmentQuizEntity q WHERE q.assignmentId = :assignmentId")
    Optional<AssignmentQuizEntity> findByAssignmentIdForUpdate(@Param("assignmentId") String assignmentId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT q FROM AssignmentQuizEntity q WHERE q.assignmentId = :assignmentId")
    Optional<AssignmentQuizEntity> findByAssignmentIdForShare(@Param("assignmentId") String assignmentId);
}
