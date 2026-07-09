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

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReadingBuddyController.class)
@Import({ReadingBuddyProperties.class, ReadingBuddyPersonaCatalog.class})
@TestPropertySource(properties = {
        "reading-buddy.enabled=false",
        "ai.chat.enabled=true"
})
class ReadingBuddyControllerDisabledTest {

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
    void status_whenFeatureDisabled_availableIsFalseEvenIfProviderReady() throws Exception {
        when(chatLlmProvider.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/reading-buddy/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)))
                .andExpect(jsonPath("$.chatEnabled", is(true)))
                .andExpect(jsonPath("$.providerAvailable", is(true)))
                .andExpect(jsonPath("$.available", is(false)));
    }

    @Test
    void chat_whenFeatureDisabled_returns403() throws Exception {
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

        verify(chatService, never()).chat(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(metricsService).recordChatRejected();
    }
}
