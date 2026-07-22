package com.classicchatreader.repository;

import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterChatConversationEntity;
import com.classicchatreader.entity.CharacterChatMessageEntity;
import com.classicchatreader.entity.CharacterChatMessageRole;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class CharacterChatPersistenceRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ChapterRepository chapterRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private CharacterChatConversationRepository conversationRepository;

    @Autowired
    private CharacterChatMessageRepository messageRepository;

    @Test
    void permitsMultipleThreadsAndKeepsConversationAndMessageReadsOwnerScopedAndOrdered() {
        UserEntity firstUser = saveUser("first@example.test");
        UserEntity secondUser = saveUser("second@example.test");
        CharacterEntity character = saveCharacter();

        CharacterChatConversationEntity older = saveConversation(
                firstUser.getId(), character.getId(), LocalDateTime.of(2026, 7, 20, 18, 0));
        CharacterChatConversationEntity newer = saveConversation(
                firstUser.getId(), character.getId(), LocalDateTime.of(2026, 7, 21, 18, 0));
        saveConversation(secondUser.getId(), character.getId(), LocalDateTime.of(2026, 7, 22, 18, 0));

        List<CharacterChatConversationEntity> firstUserThreads = conversationRepository
                .findByUserIdAndCharacterIdOrderByUpdatedAtDescCreatedAtDesc(
                        firstUser.getId(), character.getId());
        assertEquals(List.of(newer.getId(), older.getId()),
                firstUserThreads.stream().map(CharacterChatConversationEntity::getId).toList());
        assertTrue(conversationRepository.findByIdAndUserId(newer.getId(), secondUser.getId()).isEmpty());

        saveMessage(newer, 2, CharacterChatMessageRole.CHARACTER, "Second");
        saveMessage(newer, 0, CharacterChatMessageRole.SYSTEM, "Context");
        saveMessage(newer, 1, CharacterChatMessageRole.USER, "First");

        List<CharacterChatMessageEntity> transcript = messageRepository
                .findByConversationIdAndUserIdOrderBySequenceNumberAsc(newer.getId(), firstUser.getId());
        assertEquals(List.of(0L, 1L, 2L),
                transcript.stream().map(CharacterChatMessageEntity::getSequenceNumber).toList());
        assertTrue(messageRepository
                .findByConversationIdAndUserIdOrderBySequenceNumberAsc(newer.getId(), secondUser.getId())
                .isEmpty());
    }

    private UserEntity saveUser(String email) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        return userRepository.saveAndFlush(user);
    }

    private CharacterEntity saveCharacter() {
        BookEntity book = bookRepository.saveAndFlush(new BookEntity("Pride", "Austen", "manual"));
        ChapterEntity chapter = new ChapterEntity(0, "Chapter 1");
        chapter.setBook(book);
        chapter = chapterRepository.saveAndFlush(chapter);
        return characterRepository.saveAndFlush(new CharacterEntity(book, "Elizabeth", "Heroine", chapter, 0));
    }

    private CharacterChatConversationEntity saveConversation(
            String userId,
            String characterId,
            LocalDateTime updatedAt) {
        CharacterChatConversationEntity conversation = new CharacterChatConversationEntity();
        conversation.setUserId(userId);
        conversation.setCharacterId(characterId);
        conversation.setCreatedAt(updatedAt.minusMinutes(1));
        conversation.setUpdatedAt(updatedAt);
        return conversationRepository.saveAndFlush(conversation);
    }

    private void saveMessage(
            CharacterChatConversationEntity conversation,
            long sequenceNumber,
            CharacterChatMessageRole role,
            String content) {
        CharacterChatMessageEntity message = new CharacterChatMessageEntity();
        message.setConversationId(conversation.getId());
        message.setUserId(conversation.getUserId());
        message.setSequenceNumber(sequenceNumber);
        message.setRole(role);
        message.setContent(content);
        messageRepository.saveAndFlush(message);
    }
}
