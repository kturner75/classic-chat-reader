package com.classicchatreader.repository;

import com.classicchatreader.entity.BookEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BookRepository extends JpaRepository<BookEntity, String> {

    Optional<BookEntity> findBySourceAndSourceId(String source, String sourceId);

    boolean existsBySourceAndSourceId(String source, String sourceId);
}
