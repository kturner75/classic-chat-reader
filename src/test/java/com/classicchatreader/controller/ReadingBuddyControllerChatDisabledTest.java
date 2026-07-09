package com.classicchatreader.controller;

import com.classicchatreader.config.ReadingBuddyProperties;
import com.classicchatreader.service.ReaderIdentityService;
import com.classicchatreader.service.ReadingBuddyChatService;
import com.classicchatreader.service.ReadingBuddyCommentService;
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

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReadingBuddyController.class)
@Import({ReadingBuddyProperties.class, ReadingBuddyPersonaCatalog.class})
@TestPropertySource(properties = {
        "reading-buddy.enabled=true",
        "ai.chat.enabled=false"
})
class ReadingBuddyControllerChatDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "chatLlmProvider")
    private LlmProvider chatLlmProvider;

    @MockitoBean
    private ReadingBuddyPreferenceService preferenceService;

    @MockitoBean
    private ReadingBuddyChatService chatService;

    @MockitoBean
    private ReadingBuddyCommentService commentService;

    @MockitoBean
    private ReadingBuddyMemoryService memoryService;

    @MockitoBean
    private ReadingBuddyMetricsService metricsService;

    @MockitoBean
    private ReaderIdentityService readerIdentityService;

    @Test
    void status_whenChatDisabled_availableIsFalseEvenIfBuddyAndProviderReady() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/reading-buddy/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.chatEnabled", is(false)))
                .andExpect(jsonPath("$.providerAvailable", is(true)))
                .andExpect(jsonPath("$.available", is(false)));
    }

    @Test
    void chat_whenAiChatDisabled_returns403() throws Exception {
        mockMvc.perform(post("/api/reading-buddy/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookId": "book-1",
                                  "personaId": "humorist",
                                  "message": "hi",
                                  "readerChapterIndex": 0,
                                  "readerParagraphIndex": 0
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("CHAT_DISABLED")));

        verify(chatService, never()).chat(any(), any(), any(), any(), anyInt(), anyInt());
        verify(metricsService).recordChatRejected();
    }

    @Test
    void history_whenAiChatDisabled_returns403() throws Exception {
        mockMvc.perform(get("/api/reading-buddy/history")
                        .param("bookId", "book-1")
                        .param("personaId", "humorist")
                        .param("readerChapterIndex", "0")
                        .param("readerParagraphIndex", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("CHAT_DISABLED")));

        mockMvc.perform(delete("/api/reading-buddy/history")
                        .param("bookId", "book-1")
                        .param("personaId", "humorist"))
                .andExpect(status().isForbidden());
    }
}
