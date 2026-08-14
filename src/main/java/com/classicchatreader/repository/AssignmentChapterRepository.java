package com.classicchatreader.repository;

import com.classicchatreader.entity.AssignmentChapterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssignmentChapterRepository extends JpaRepository<AssignmentChapterEntity, String> {
    List<AssignmentChapterEntity> findByAssignmentIdOrderBySortOrderAscChapterIndexAsc(String assignmentId);

    void deleteByAssignmentId(String assignmentId);
}
