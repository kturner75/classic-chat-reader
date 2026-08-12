package com.classicchatreader.repository;

import com.classicchatreader.entity.ClassroomUsageEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassroomUsageEventRepository extends JpaRepository<ClassroomUsageEventEntity, String> {

    Optional<ClassroomUsageEventEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT COALESCE(SUM(e.durationMs), 0)
            FROM ClassroomUsageEventEntity e
            WHERE e.termId = :termId
              AND e.userId = :userId
              AND e.eventType = :eventType
              AND e.deletedAt IS NULL
              AND e.durationMs IS NOT NULL
            """)
    long sumDurationMsByTermUserAndType(
            @Param("termId") String termId,
            @Param("userId") String userId,
            @Param("eventType") String eventType);

    @Query("""
            SELECT e.bookId, COALESCE(SUM(e.durationMs), 0)
            FROM ClassroomUsageEventEntity e
            WHERE e.termId = :termId
              AND e.userId = :userId
              AND e.eventType = :eventType
              AND e.deletedAt IS NULL
              AND e.durationMs IS NOT NULL
              AND e.bookId IS NOT NULL
            GROUP BY e.bookId
            """)
    List<Object[]> sumDurationMsByBook(
            @Param("termId") String termId,
            @Param("userId") String userId,
            @Param("eventType") String eventType);
}
