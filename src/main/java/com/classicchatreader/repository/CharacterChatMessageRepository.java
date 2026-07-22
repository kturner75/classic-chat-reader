package com.classicchatreader.repository;

import com.classicchatreader.entity.CharacterChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CharacterChatMessageRepository extends JpaRepository<CharacterChatMessageEntity, String> {

    @Query("""
            SELECT m.session.id AS sessionId, COUNT(m.id) AS messageCount
            FROM CharacterChatMessageEntity m
            WHERE m.session.id IN :sessionIds
            GROUP BY m.session.id
            """)
    List<SessionMessageCount> countForSessions(@Param("sessionIds") Collection<String> sessionIds);

    @Query("""
            SELECT m FROM CharacterChatMessageEntity m
            WHERE m.session.id IN :sessionIds
              AND m.content IS NOT NULL
              AND TRIM(m.content) <> ''
              AND NOT EXISTS (
                    SELECT newer.id FROM CharacterChatMessageEntity newer
                    WHERE newer.session = m.session
                      AND newer.content IS NOT NULL
                      AND TRIM(newer.content) <> ''
                      AND (newer.createdAt > m.createdAt
                           OR (newer.createdAt = m.createdAt AND newer.id > m.id))
              )
            """)
    List<CharacterChatMessageEntity> findNewestNonblankForSessions(
            @Param("sessionIds") Collection<String> sessionIds
    );

    @Query("""
            SELECT m FROM CharacterChatMessageEntity m
            WHERE m.session.id = :sessionId
              AND m.session.ownerUserId = :ownerUserId
              AND m.session.deleted = false
            ORDER BY m.createdAt ASC, m.id ASC
            """)
    List<CharacterChatMessageEntity> findOwnedTranscript(
            @Param("sessionId") String sessionId,
            @Param("ownerUserId") String ownerUserId
    );

    interface SessionMessageCount {
        String getSessionId();
        long getMessageCount();
    }
}
