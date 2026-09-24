package com.classicchatreader.repository;

import com.classicchatreader.entity.CuratedBookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CuratedBookRepository extends JpaRepository<CuratedBookEntity, String> {

    List<CuratedBookEntity> findBySourceAndStatus(String source, String status);

    List<CuratedBookEntity> findBySource(String source);

    Optional<CuratedBookEntity> findBySourceAndSourceId(String source, String sourceId);
}
