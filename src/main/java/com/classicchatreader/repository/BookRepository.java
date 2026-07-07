package com.classicchatreader.repository;

import com.classicchatreader.entity.BookEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookRepository extends JpaRepository<BookEntity, String> {

    Optional<BookEntity> findBySourceAndSourceId(String source, String sourceId);

    List<BookEntity> findBySourceAndSourceIdIn(String source, Collection<String> sourceIds);

    boolean existsBySourceAndSourceId(String source, String sourceId);
}
