package com.classicchatreader.repository;

import com.classicchatreader.entity.CharacterChatMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
