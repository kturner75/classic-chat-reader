package com.classicchatreader.repository;

import com.classicchatreader.entity.EducationRecordAccessLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EducationRecordAccessLogRepository extends JpaRepository<EducationRecordAccessLogEntity, String> {

    List<EducationRecordAccessLogEntity> findBySubjectUserIdOrderByOccurredAtDesc(String subjectUserId);

    List<EducationRecordAccessLogEntity> findByActorUserIdOrderByOccurredAtDesc(String actorUserId);
}
