package com.classicchatreader.repository;

import com.classicchatreader.entity.ClassFeatureSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClassFeatureSettingsRepository extends JpaRepository<ClassFeatureSettingsEntity, String> {
}
