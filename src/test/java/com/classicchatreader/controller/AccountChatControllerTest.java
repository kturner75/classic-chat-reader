package com.classicchatreader.controller;

import com.classicchatreader.model.AccountChatModels.BookIdentity;
import com.classicchatreader.model.AccountChatModels.ChatContext;
import com.classicchatreader.model.AccountChatModels.CharacterIdentity;
import com.classicchatreader.model.AccountChatModels.ContinueResponse;
import com.classicchatreader.model.AccountChatModels.Message;
import com.classicchatreader.model.AccountChatModels.PageInfo;
import com.classicchatreader.model.AccountChatModels.Resume;
import com.classicchatreader.model.AccountChatModels.SessionListResponse;
import com.classicchatreader.model.AccountChatModels.SessionSummary;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.AccountChatHistoryService;
import com.classicchatreader.service.ChatHistoryValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountChatController.class)
class AccountChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountAuthService accountAuthService;

    @MockitoBean
    private AccountChatHistoryService chatHistoryService;

    @Test
    void listRequiresAuthenticatedAccountAndDisablesCaching() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/account/chats"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.error.code", is("UNAUTHORIZED")));
    }

    @Test
    void detailRequiresAuthenticatedAccount() throws Exception {
        when(accountAuthService.resolveAuthenticatedPrincipal(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/account/chats/chat-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.error.code", is("UNAUTHORIZED")));
    }

    @Test
    void listUsesAuthenticatedUserAndReturnsContract() throws Exception {
        authenticate("user-1");
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        SessionSummary summary = new SessionSummary(
                "chat-1",
                new CharacterIdentity("character-1", "Elizabeth", "/api/characters/character-1/portrait"),
                new BookIdentity("book-1", "Pride and Prejudice", "Jane Austen"),
                "Hello",
                "CHARACTER",
                2,
                now,
                now,
                now,
                new ChatContext("chapter-1", 0, "Chapter One", 4),
                new Resume(true, "/my-chats?session=chat-1", null)
        );
        when(chatHistoryService.list(eq("user-1"), any()))
                .thenReturn(new SessionListResponse(List.of(summary), new PageInfo(4, null, false)));

        mockMvc.perform(get("/api/account/chats").param("limit", "4"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.items[0].sessionId", is("chat-1")))
                .andExpect(jsonPath("$.items[0].resume.url", is("/my-chats?session=chat-1")))
                .andExpect(jsonPath("$.page.limit", is(4)));

        verify(chatHistoryService).list(eq("user-1"), any());
    }

    @Test
    void filtersUsesAuthenticatedUserAndReturnsCompleteCatalog() throws Exception {
        authenticate("user-1");
        when(chatHistoryService.filterOptions("user-1")).thenReturn(
                new com.classicchatreader.model.AccountChatModels.FilterOptionsResponse(
                        List.of(new com.classicchatreader.model.AccountChatModels.FilterOption(
                                "book-1", "Pride and Prejudice")),
                        List.of(new com.classicchatreader.model.AccountChatModels.CharacterFilterOption(
                                "character-1", "Elizabeth", "book-1"))
                )
        );

        mockMvc.perform(get("/api/account/chats/filters"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.books[0].id", is("book-1")))
                .andExpect(jsonPath("$.books[0].label", is("Pride and Prejudice")))
                .andExpect(jsonPath("$.characters[0].id", is("character-1")))
                .andExpect(jsonPath("$.characters[0].bookId", is("book-1")));

        verify(chatHistoryService).filterOptions("user-1");
    }

    @Test
    void malformedParametersUseDocumentedErrorEnvelope() throws Exception {
        authenticate("user-1");
        when(chatHistoryService.list(eq("user-1"), any()))
                .thenThrow(new ChatHistoryValidationException("INVALID_CURSOR", "The page cursor is invalid for this request."));

        mockMvc.perform(get("/api/account/chats").param("cursor", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", is("INVALID_CURSOR")));
    }

    @Test
    void anotherUsersSessionAndMissingSessionAreBothNotFound() throws Exception {
        authenticate("user-1");
        when(chatHistoryService.get("user-1", "other-users-chat")).thenReturn(null);
        when(chatHistoryService.get("user-1", "missing-chat")).thenReturn(null);

        mockMvc.perform(get("/api/account/chats/other-users-chat"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("CHAT_NOT_FOUND")));
        mockMvc.perform(get("/api/account/chats/missing-chat"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("CHAT_NOT_FOUND")));
    }

    @Test
    void continueConversationUsesAuthenticatedOwnerAndExistingSession() throws Exception {
        authenticate("user-1");
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        ContinueResponse response = new ContinueResponse(
                new Message("message-user", "USER", "Hello again", now),
                new Message("message-character", "CHARACTER", "Welcome back", now.plusMillis(1)),
                new ChatContext("chapter-1", 0, "Chapter One", 4),
                now.plusMillis(1));
        when(chatHistoryService.continueConversation(eq("user-1"), eq("chat-1"), any(), eq("request-1")))
                .thenReturn(response);

        mockMvc.perform(post("/api/account/chats/chat-1/messages")
                        .header("Idempotency-Key", "request-1")
                        .contentType("application/json")
                        .content("""
                                {"content":"Hello again","context":{"chapterId":"chapter-1","chapterIndex":0,"chapterTitle":"Chapter One","paragraphIndex":4}}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.userMessage.content", is("Hello again")))
                .andExpect(jsonPath("$.characterMessage.content", is("Welcome back")));

        verify(chatHistoryService).continueConversation(eq("user-1"), eq("chat-1"), any(), eq("request-1"));
    }

    @Test
    void continueConversationDoesNotRevealAnotherUsersSession() throws Exception {
        authenticate("user-1");
        when(chatHistoryService.continueConversation(eq("user-1"), eq("other-users-chat"), any(), any()))
                .thenReturn(null);

        mockMvc.perform(post("/api/account/chats/other-users-chat/messages")
                        .contentType("application/json")
                        .content("{\"content\":\"Hello\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("CHAT_NOT_FOUND")));
    }

    private void authenticate(String userId) {
        when(accountAuthService.resolveAuthenticatedPrincipal(any()))
                .thenReturn(Optional.of(new AccountAuthService.AccountPrincipal(userId, "reader@example.com")));
    }
}
