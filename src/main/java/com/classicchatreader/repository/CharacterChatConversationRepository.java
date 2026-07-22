package com.classicchatreader.repository;

import com.classicchatreader.entity.CharacterChatConversationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface CharacterChatConversationRepository extends JpaRepository<CharacterChatConversationEntity, String> {

    Optional<CharacterChatConversationEntity> findByIdAndUserId(String id, String userId);

    List<CharacterChatConversationEntity> findByUserIdAndCharacterIdOrderByUpdatedAtDescCreatedAtDesc(
            String userId,
            String characterId
    );

    List<CharacterChatConversationEntity> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    @Query("""
            SELECT conversation FROM CharacterChatConversationEntity conversation
            JOIN CharacterEntity character ON character.id = conversation.characterId
            JOIN character.book book
            WHERE conversation.userId = :userId
              AND EXISTS (SELECT userMessage.id FROM CharacterChatMessageEntity userMessage
                          WHERE userMessage.conversationId = conversation.id
                            AND userMessage.userId = :userId
                            AND userMessage.role = com.classicchatreader.entity.CharacterChatMessageRole.USER)
              AND (:bookId IS NULL OR book.id = :bookId)
              AND (:characterId IS NULL OR character.id = :characterId)
              AND (:activeAfter IS NULL OR conversation.updatedAt >= :activeAfter)
              AND (:activeBefore IS NULL OR conversation.updatedAt < :activeBefore)
              AND (:q IS NULL
                   OR LOWER(character.name) LIKE :q ESCAPE '\\'
                   OR LOWER(book.title) LIKE :q ESCAPE '\\'
                   OR LOWER(book.author) LIKE :q ESCAPE '\\'
                   OR EXISTS (SELECT matchingMessage.id FROM CharacterChatMessageEntity matchingMessage
                              WHERE matchingMessage.conversationId = conversation.id
                                AND matchingMessage.userId = :userId
                                AND LOWER(matchingMessage.content) LIKE :q ESCAPE '\\'))
              AND (:cursorTime IS NULL
                   OR conversation.updatedAt < :cursorTime
                   OR (conversation.updatedAt = :cursorTime AND conversation.id > :cursorId))
            ORDER BY conversation.updatedAt DESC, conversation.id ASC
            """)
    List<CharacterChatConversationEntity> findVisiblePage(
            @Param("userId") String userId,
            @Param("q") String q,
            @Param("bookId") String bookId,
            @Param("characterId") String characterId,
            @Param("activeAfter") LocalDateTime activeAfter,
            @Param("activeBefore") LocalDateTime activeBefore,
            @Param("cursorTime") LocalDateTime cursorTime,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    @Query("""
            SELECT DISTINCT book.id AS id, book.title AS title
            FROM CharacterChatConversationEntity conversation
            JOIN CharacterEntity character ON character.id = conversation.characterId
            JOIN character.book book
            WHERE conversation.userId = :userId
              AND EXISTS (SELECT userMessage.id FROM CharacterChatMessageEntity userMessage
                          WHERE userMessage.conversationId = conversation.id
                            AND userMessage.userId = :userId
                            AND userMessage.role = com.classicchatreader.entity.CharacterChatMessageRole.USER)
            ORDER BY book.title ASC
            """)
    List<BookFilterRow> findVisibleFilterBooks(@Param("userId") String userId);

    @Query("""
            SELECT DISTINCT character.id AS id, character.name AS name, book.id AS bookId
            FROM CharacterChatConversationEntity conversation
            JOIN CharacterEntity character ON character.id = conversation.characterId
            JOIN character.book book
            WHERE conversation.userId = :userId
              AND EXISTS (SELECT userMessage.id FROM CharacterChatMessageEntity userMessage
                          WHERE userMessage.conversationId = conversation.id
                            AND userMessage.userId = :userId
                            AND userMessage.role = com.classicchatreader.entity.CharacterChatMessageRole.USER)
            ORDER BY character.name ASC
            """)
    List<CharacterFilterRow> findVisibleFilterCharacters(@Param("userId") String userId);

    interface BookFilterRow {
        String getId();
        String getTitle();
    }

    interface CharacterFilterRow {
        String getId();
        String getName();
        String getBookId();
    }
}
