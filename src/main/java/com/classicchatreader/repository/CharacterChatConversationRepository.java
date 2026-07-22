package com.classicchatreader.repository;

import com.classicchatreader.entity.CharacterChatConversationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CharacterChatConversationRepository extends JpaRepository<CharacterChatConversationEntity, String> {

    Optional<CharacterChatConversationEntity> findByIdAndUserId(String id, String userId);

    List<CharacterChatConversationEntity> findByUserIdAndCharacterIdOrderByUpdatedAtDescCreatedAtDesc(
            String userId,
            String characterId
    );

    List<CharacterChatConversationEntity> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);
}
