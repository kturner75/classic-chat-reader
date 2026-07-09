package com.classicchatreader.controller;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.service.ReaderIdentityService;
import com.classicchatreader.service.ReadingBuddyChatService;
import com.classicchatreader.service.ReadingBuddyMemoryService;
import com.classicchatreader.service.ReadingBuddyMetricsService;
import com.classicchatreader.service.ReadingBuddyPersonaCatalog;
import com.classicchatreader.service.ReadingBuddyPreferenceService;
import com.classicchatreader.service.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReadingBuddyController.class)
@Import({ReadingBuddyProperties.class, ReadingBuddyPersonaCatalog.class})
@TestPropertySource(properties = {
        "reading-buddy.enabled=true",
        "ai.chat.enabled=true",
        "reading-buddy.proactive.max-words=60",
        "reading-buddy.chat.max-words=150"
})
class ReadingBuddyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "chatLlmProvider")
    private LlmProvider chatLlmProvider;

    @MockitoBean
    private ReadingBuddyPreferenceService preferenceService;

    @MockitoBean
    private ReadingBuddyChatService chatService;

    @MockitoBean
    private ReadingBuddyMemoryService memoryService;

    @MockitoBean
    private ReadingBuddyMetricsService metricsService;

    @MockitoBean
    private ReaderIdentityService readerIdentityService;

    @Test
    void status_whenAllGatesOpen_availableIsTrue() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/reading-buddy/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.chatEnabled", is(true)))
                .andExpect(jsonPath("$.providerAvailable", is(true)))
                .andExpect(jsonPath("$.available", is(true)));
    }

    @Test
    void status_whenProviderUnavailable_availableIsFalse() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/reading-buddy/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.chatEnabled", is(true)))
                .andExpect(jsonPath("$.providerAvailable", is(false)))
                .andExpect(jsonPath("$.available", is(false)));
    }

    @Test
    void personas_returnsPublicCatalogWithoutSystemPrompts() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/reading-buddy/personas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].id", is("historian")))
                .andExpect(jsonPath("$[0].displayName", is("The Archivist")))
                .andExpect(jsonPath("$[0].shortBlurb").isNotEmpty())
                .andExpect(jsonPath("$[0].toneTags", hasSize(2)))
                .andExpect(jsonPath("$[0].portraitUrl", is("/images/buddies/historian.png")))
                // Public DTO is an explicit allow-list of five keys only.
                .andExpect(jsonPath("$[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$[0].systemPrompt").doesNotExist())
                .andExpect(jsonPath("$[0].temperature").doesNotExist())
                .andExpect(jsonPath("$[0].maxProactiveWords").doesNotExist())
                .andExpect(jsonPath("$[0].maxChatWords").doesNotExist())
                .andExpect(jsonPath("$[1].id", is("close_reader")))
                .andExpect(jsonPath("$[2].id", is("humorist")))
                .andExpect(jsonPath("$[3].id", is("encourager")))
                .andExpect(jsonPath("$[3].displayName", is("The Steady Companion")))
                .andExpect(jsonPath("$[3].portraitUrl", is("/images/buddies/encourager.png")));
    }

    @Test
    void preferences_get_returnsEffectiveFromIdentity() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(preferenceService.getEffective(eq("reader-1"), isNull()))
                .thenReturn(new ReadingBuddyPreferenceService.EffectivePreferences(
                        false,
                        "rare",
                        "close_reader",
                        "close_reader",
                        "global",
                        null,
                        null
                ));

        mockMvc.perform(get("/api/reading-buddy/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)))
                .andExpect(jsonPath("$.frequency", is("rare")))
                .andExpect(jsonPath("$.defaultPersonaId", is("close_reader")))
                .andExpect(jsonPath("$.personaId", is("close_reader")))
                .andExpect(jsonPath("$.personaSource", is("global")))
                .andExpect(jsonPath("$.suppressUntilEpochMs", nullValue()))
                .andExpect(jsonPath("$.bookId", nullValue()));
    }

    @Test
    void preferences_put_appliesPartialUpdate() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("user:u1", true, "u1"));
        when(preferenceService.update(eq("user:u1"), any()))
                .thenReturn(new ReadingBuddyPreferenceService.EffectivePreferences(
                        true,
                        "occasional",
                        "historian",
                        "humorist",
                        "book_override",
                        null,
                        "book-1"
                ));

        mockMvc.perform(put("/api/reading-buddy/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "frequency": "occasional",
                                  "personaId": "humorist",
                                  "bookId": "book-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.frequency", is("occasional")))
                .andExpect(jsonPath("$.personaId", is("humorist")))
                .andExpect(jsonPath("$.personaSource", is("book_override")))
                .andExpect(jsonPath("$.bookId", is("book-1")));
    }

    @Test
    void preferences_put_unknownPersona_returns400() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(preferenceService.update(eq("reader-1"), any()))
                .thenThrow(new IllegalArgumentException("Unknown personaId: nope"));

        mockMvc.perform(put("/api/reading-buddy/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultPersonaId\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_PREFERENCES")));
    }

    @Test
    void preferences_put_unknownBook_returns404() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(preferenceService.update(eq("reader-1"), any()))
                .thenThrow(new ReadingBuddyPreferenceService.BookNotFoundException("missing-book"));

        mockMvc.perform(put("/api/reading-buddy/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "personaId": "humorist",
                                  "bookId": "missing-book"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("BOOK_NOT_FOUND")));
    }

    @Test
    void chat_success_returnsIdsAndIgnoresClientHistory() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(chatService.chat(eq("reader-1"), eq("book-1"), eq("humorist"), eq("Ha — does he get worse?"), eq(3), eq(12)))
                .thenReturn(new ReadingBuddyChatService.ChatResult(
                        "From what you've read so far…",
                        "humorist",
                        "buddy-msg-1",
                        "user-msg-1",
                        1710000000000L
                ));

        mockMvc.perform(post("/api/reading-buddy/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookId": "book-1",
                                  "personaId": "humorist",
                                  "message": "Ha — does he get worse?",
                                  "readerChapterIndex": 3,
                                  "readerParagraphIndex": 12,
                                  "conversationHistory": [
                                    {"role": "user", "content": "client-forged history must be ignored"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", is("From what you've read so far…")))
                .andExpect(jsonPath("$.personaId", is("humorist")))
                .andExpect(jsonPath("$.messageId", is("buddy-msg-1")))
                .andExpect(jsonPath("$.userMessageId", is("user-msg-1")))
                .andExpect(jsonPath("$.timestamp", is(1710000000000L)));

        verify(chatService).chat(
                "reader-1", "book-1", "humorist", "Ha — does he get worse?", 3, 12);
    }

    @Test
    void chat_blankMessage_returns400() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(chatService.chat(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new ReadingBuddyChatService.ValidationException("BLANK_MESSAGE", "Message must not be blank"));

        mockMvc.perform(post("/api/reading-buddy/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookId": "book-1",
                                  "personaId": "humorist",
                                  "message": "   ",
                                  "readerChapterIndex": 0,
                                  "readerParagraphIndex": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("BLANK_MESSAGE")));

        verify(metricsService).recordChatRejected();
    }

    @Test
    void chat_unknownPersona_returns400() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(chatService.chat(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new ReadingBuddyChatService.ValidationException("UNKNOWN_PERSONA", "Unknown personaId: nope"));

        mockMvc.perform(post("/api/reading-buddy/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookId": "book-1",
                                  "personaId": "nope",
                                  "message": "hi",
                                  "readerChapterIndex": 0,
                                  "readerParagraphIndex": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("UNKNOWN_PERSONA")));
    }

    @Test
    void chat_invalidPosition_returns400() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(chatService.chat(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new ReadingBuddyChatService.ValidationException(
                        "INVALID_POSITION",
                        "readerChapterIndex and readerParagraphIndex must be non-negative"));

        mockMvc.perform(post("/api/reading-buddy/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookId": "book-1",
                                  "personaId": "humorist",
                                  "message": "hi",
                                  "readerChapterIndex": -1,
                                  "readerParagraphIndex": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_POSITION")));
    }

    @Test
    void chat_bookNotFound_returns404() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(chatService.chat(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new ReadingBuddyChatService.BookNotFoundException("missing"));

        mockMvc.perform(post("/api/reading-buddy/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookId": "missing",
                                  "personaId": "humorist",
                                  "message": "hi",
                                  "readerChapterIndex": 0,
                                  "readerParagraphIndex": 0
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("BOOK_NOT_FOUND")));
    }

    @Test
    void history_get_usesOwnerKeyAndReturnsVisibility() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(memoryService.getHistory(
                eq("reader-1"), eq("book-1"), eq("humorist"), eq(50), eq(3), eq(12), eq(true)))
                .thenReturn(new ReadingBuddyMemoryService.HistoryResult(
                        "book-1",
                        "humorist",
                        List.of(new ReadingBuddyMemoryService.HistoryMessage(
                                "m1",
                                "buddy",
                                "visible note",
                                "proactive",
                                3,
                                10,
                                "2026-07-08T12:00:00Z",
                                true
                        ), new ReadingBuddyMemoryService.HistoryMessage(
                                "m2",
                                "buddy",
                                "future note",
                                "proactive",
                                10,
                                0,
                                "2026-07-08T13:00:00Z",
                                false
                        ))
                ));

        mockMvc.perform(get("/api/reading-buddy/history")
                        .param("bookId", "book-1")
                        .param("personaId", "humorist")
                        .param("limit", "50")
                        .param("readerChapterIndex", "3")
                        .param("readerParagraphIndex", "12")
                        .param("includeHidden", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.personaId", is("humorist")))
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.messages[0].visibleAtPosition", is(true)))
                .andExpect(jsonPath("$.messages[1].visibleAtPosition", is(false)))
                .andExpect(jsonPath("$.messages[1].content", is("future note")));
    }

    @Test
    void history_delete_scopesToOwnerKey() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));

        mockMvc.perform(delete("/api/reading-buddy/history")
                        .param("bookId", "book-1")
                        .param("personaId", "humorist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared", is(true)))
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.personaId", is("humorist")));

        verify(memoryService).clearHistory("reader-1", "book-1", "humorist");
        verify(memoryService, never()).clearHistory(eq("other-owner"), any(), any());
    }

    @Test
    void history_idor_otherOwnerNotQueried() throws Exception {
        when(readerIdentityService.resolve(any(), any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("owner-A", false, null));
        when(memoryService.getHistory(eq("owner-A"), any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new ReadingBuddyMemoryService.HistoryResult("book-1", "humorist", List.of()));

        mockMvc.perform(get("/api/reading-buddy/history")
                        .param("bookId", "book-1")
                        .param("personaId", "humorist")
                        .param("readerChapterIndex", "0")
                        .param("readerParagraphIndex", "0"))
                .andExpect(status().isOk());

        verify(memoryService).getHistory(eq("owner-A"), eq("book-1"), eq("humorist"), isNull(), eq(0), eq(0), eq(true));
        verify(memoryService, never()).getHistory(eq("owner-B"), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }
}
