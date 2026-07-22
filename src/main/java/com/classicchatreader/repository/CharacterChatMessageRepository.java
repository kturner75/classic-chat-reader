package com.classicchatreader.repository;

import com.classicchatreader.entity.CharacterChatMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CharacterChatMessageRepository extends JpaRepository<CharacterChatMessageEntity, String> {

    Optional<CharacterChatMessageEntity> findByIdAndConversationIdAndUserId(
            String id,
            String conversationId,
            String userId
    );

    List<CharacterChatMessageEntity> findByConversationIdAndUserIdOrderBySequenceNumberAsc(
            String conversationId,
            String userId
    );

    List<CharacterChatMessageEntity> findByConversationIdAndUserIdOrderBySequenceNumberDesc(
            String conversationId,
            String userId,
            Pageable pageable
    );

    Optional<CharacterChatMessageEntity> findByConversationIdAndUserIdAndClientMessageId(
            String conversationId,
            String userId,
            String clientMessageId
    );

    @Query("""
            SELECT message.conversationId AS conversationId, COUNT(message.id) AS messageCount
            FROM CharacterChatMessageEntity message
            WHERE message.conversationId IN :conversationIds
            GROUP BY message.conversationId
            """)
    List<ConversationMessageCount> countForConversations(
            @Param("conversationIds") List<String> conversationIds
    );

    @Query("""
            SELECT message FROM CharacterChatMessageEntity message
            WHERE message.conversationId IN :conversationIds
              AND message.content IS NOT NULL
              AND TRIM(message.content) <> ''
              AND NOT EXISTS (
                  SELECT newer.id FROM CharacterChatMessageEntity newer
                  WHERE newer.conversationId = message.conversationId
                    AND newer.content IS NOT NULL
                    AND TRIM(newer.content) <> ''
                    AND newer.sequenceNumber > message.sequenceNumber
              )
            """)
    List<CharacterChatMessageEntity> findNewestNonblankForConversations(
            @Param("conversationIds") List<String> conversationIds
    );

    interface ConversationMessageCount {
        String getConversationId();
        long getMessageCount();
    }
}
