package com.classicchatreader.controller;

import com.classicchatreader.config.DataInitializer;
import com.classicchatreader.config.SearchIndexInitializer;
import com.classicchatreader.entity.BookEntity;
import com.classicchatreader.entity.ChapterEntity;
import com.classicchatreader.entity.CharacterEntity;
import com.classicchatreader.entity.CharacterStatus;
import com.classicchatreader.entity.CharacterType;
import com.classicchatreader.entity.UserEntity;
import com.classicchatreader.repository.BookRepository;
import com.classicchatreader.repository.ChapterRepository;
import com.classicchatreader.repository.CharacterChatConversationRepository;
import com.classicchatreader.repository.CharacterChatMessageRepository;
import com.classicchatreader.repository.CharacterRepository;
import com.classicchatreader.repository.UserRepository;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.CharacterChatService;
import com.classicchatreader.service.ChatHistoryValidationException;
import com.classicchatreader.service.ClassroomContextService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ai.chat.enabled=true",
        "character.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
class CharacterChatPersistenceEndToEndTest {

    private static final String USER_HEADER = "X-Test-User";

    @Autowired
    private MockMvc mockMvc;

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

    @MockitoBean
    private AccountAuthService accountAuthService;

    @MockitoBean
    private CharacterChatService characterChatService;

    @MockitoBean
    private ClassroomContextService classroomContextService;

    @MockitoBean
    private DataInitializer dataInitializer;

    @MockitoBean
    private SearchIndexInitializer searchIndexInitializer;

    private String firstUserId;
    private String secondUserId;
    private String firstCharacterId;
    private String secondCharacterId;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        characterRepository.deleteAll();
        chapterRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();

        firstUserId = saveUser("first@example.test").getId();
        secondUserId = saveUser("second@example.test").getId();

        BookEntity book = new BookEntity("Pride and Prejudice", "Jane Austen", "manual");
        book.setCharacterEnabled(true);
        book = bookRepository.saveAndFlush(book);

        ChapterEntity chapter = new ChapterEntity(0, "Chapter One");
        chapter.setBook(book);
        chapter = chapterRepository.saveAndFlush(chapter);

        firstCharacterId = saveCharacter(book, chapter, "Elizabeth Bennet").getId();
        secondCharacterId = saveCharacter(book, chapter, "Mr. Darcy").getId();

        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenAnswer(invocation -> {
            HttpServletRequest request = invocation.getArgument(0, HttpServletRequest.class);
            String userId = request.getHeader(USER_HEADER);
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AccountAuthService.AccountPrincipal(userId, "reader@example.test"));
        });
        when(characterChatService.chat(anyString(), anyString(), anyList(), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Reply to " + invocation.getArgument(1, String.class));
    }

    @Test
    void transcriptPersistsAcrossStatelessDeviceRequestsAndRemainsIsolatedAndOrdered() throws Exception {
        getTranscript(firstUserId, firstCharacterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").doesNotExist())
                .andExpect(jsonPath("$.messages").isEmpty());

        send(firstUserId, firstCharacterId, "request-1", "Hello")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userMessage.content", is("Hello")))
                .andExpect(jsonPath("$.characterMessage.content", is("Reply to Hello")));
        send(firstUserId, firstCharacterId, "request-2", "Do you remember me?")
                .andExpect(status().isOk());

        // A network replay from the first device must return the persisted exchange, not append it.
        send(firstUserId, firstCharacterId, "request-2", "Do you remember me?")
                .andExpect(status().isOk());

        // These independent GETs carry no browser cookie or localStorage state. The user header stands
        // in for each device's authenticated session, so the transcript can only come from the database.
        getTranscript(firstUserId, firstCharacterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()", is(4)))
                .andExpect(jsonPath("$.messages[0].role", is("USER")))
                .andExpect(jsonPath("$.messages[0].content", is("Hello")))
                .andExpect(jsonPath("$.messages[1].role", is("CHARACTER")))
                .andExpect(jsonPath("$.messages[1].content", is("Reply to Hello")))
                .andExpect(jsonPath("$.messages[2].role", is("USER")))
                .andExpect(jsonPath("$.messages[2].content", is("Do you remember me?")))
                .andExpect(jsonPath("$.messages[3].role", is("CHARACTER")))
                .andExpect(jsonPath("$.messages[3].content", is("Reply to Do you remember me?")));
        getTranscript(firstUserId, firstCharacterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()", is(4)));

        getTranscript(secondUserId, firstCharacterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").doesNotExist())
                .andExpect(jsonPath("$.messages").isEmpty());
        getTranscript(firstUserId, secondCharacterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").doesNotExist())
                .andExpect(jsonPath("$.messages").isEmpty());

        verify(characterChatService, times(2))
                .chat(eq(firstCharacterId), anyString(), anyList(), anyInt(), anyInt());
    }

    @Test
    void failedSendRollsBackAndRetryWithSameRequestIdPersistsExactlyOnce() throws Exception {
        when(characterChatService.chat(eq(firstCharacterId), eq("Retry me"), anyList(), anyInt(), anyInt()))
                .thenThrow(new ChatHistoryValidationException("CHAT_TEMPORARY", "Please retry."))
                .thenReturn("Recovered reply");

        send(firstUserId, firstCharacterId, "retry-request", "Retry me")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", is("CHAT_TEMPORARY")));
        getTranscript(firstUserId, firstCharacterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty());

        send(firstUserId, firstCharacterId, "retry-request", "Retry me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characterMessage.content", is("Recovered reply")));
        send(firstUserId, firstCharacterId, "retry-request", "Retry me")
                .andExpect(status().isOk());

        getTranscript(firstUserId, firstCharacterId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()", is(2)))
                .andExpect(jsonPath("$.messages[0].content", is("Retry me")))
                .andExpect(jsonPath("$.messages[1].content", is("Recovered reply")));
        verify(characterChatService, times(2))
                .chat(eq(firstCharacterId), eq("Retry me"), anyList(), anyInt(), anyInt());
    }

    private org.springframework.test.web.servlet.ResultActions getTranscript(String userId, String characterId)
            throws Exception {
        return mockMvc.perform(get("/api/account/chats/characters/{characterId}", characterId)
                .header(USER_HEADER, userId));
    }

    private org.springframework.test.web.servlet.ResultActions send(
            String userId,
            String characterId,
            String requestId,
            String content) throws Exception {
        return mockMvc.perform(post("/api/account/chats/characters/{characterId}/messages", characterId)
                .header(USER_HEADER, userId)
                .header("Idempotency-Key", requestId)
                .contentType("application/json")
                .content("{\"content\":\"" + content.replace("\"", "\\\"") + "\"}"));
    }

    private UserEntity saveUser(String email) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        return userRepository.saveAndFlush(user);
    }

    private CharacterEntity saveCharacter(BookEntity book, ChapterEntity chapter, String name) {
        CharacterEntity character = new CharacterEntity(book, name, "A character", chapter, 0);
        character.setStatus(CharacterStatus.COMPLETED);
        character.setCharacterType(CharacterType.PRIMARY);
        return characterRepository.saveAndFlush(character);
    }
}
