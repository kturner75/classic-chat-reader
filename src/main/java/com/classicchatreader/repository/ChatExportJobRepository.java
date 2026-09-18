package com.classicchatreader.repository;

import com.classicchatreader.entity.ChatExportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatExportJobRepository extends JpaRepository<ChatExportJobEntity, String> {

    List<ChatExportJobEntity> findBySubjectUserIdOrderByCreatedAtDesc(String subjectUserId);
}
