package com.classicchatreader.repository;

import com.classicchatreader.entity.CharacterChatSessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CharacterChatSessionRepository extends JpaRepository<CharacterChatSessionEntity, String> {

    @Query("""
            SELECT s FROM CharacterChatSessionEntity s
            JOIN FETCH s.book b
            JOIN FETCH s.character c
            JOIN FETCH s.contextChapter ch
            WHERE s.ownerUserId = :ownerUserId
              AND s.deleted = false
              AND EXISTS (SELECT um.id FROM CharacterChatMessageEntity um
                          WHERE um.session = s AND um.role = 'USER')
              AND (:bookId IS NULL OR b.id = :bookId)
              AND (:characterId IS NULL OR c.id = :characterId)
              AND (:activeAfter IS NULL OR s.lastMessageAt >= :activeAfter)
              AND (:activeBefore IS NULL OR s.lastMessageAt < :activeBefore)
              AND (:q IS NULL
                   OR LOWER(s.characterNameSnapshot) LIKE :q ESCAPE '\\'
                   OR LOWER(s.bookTitleSnapshot) LIKE :q ESCAPE '\\'
                   OR LOWER(s.bookAuthorSnapshot) LIKE :q ESCAPE '\\'
                   OR EXISTS (SELECT qm.id FROM CharacterChatMessageEntity qm
                              WHERE qm.session = s AND LOWER(qm.content) LIKE :q ESCAPE '\\'))
              AND (:cursorTime IS NULL
                   OR s.lastMessageAt < :cursorTime
                   OR (s.lastMessageAt = :cursorTime AND s.id > :cursorId))
            ORDER BY s.lastMessageAt DESC, s.id ASC
            """)
    List<CharacterChatSessionEntity> findVisiblePage(
            @Param("ownerUserId") String ownerUserId,
            @Param("q") String q,
            @Param("bookId") String bookId,
            @Param("characterId") String characterId,
            @Param("activeAfter") LocalDateTime activeAfter,
            @Param("activeBefore") LocalDateTime activeBefore,
            @Param("cursorTime") LocalDateTime cursorTime,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"book", "character", "contextChapter"})
    Optional<CharacterChatSessionEntity> findByIdAndOwnerUserIdAndDeletedFalse(String id, String ownerUserId);

    @EntityGraph(attributePaths = {"book", "character", "contextChapter"})
    Optional<CharacterChatSessionEntity> findByOwnerUserIdAndBookIdAndCharacterIdAndDeletedFalse(
            String ownerUserId,
            String bookId,
            String characterId
    );
}
