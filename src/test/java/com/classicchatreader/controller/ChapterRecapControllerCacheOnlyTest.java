package com.classicchatreader.controller;

import com.classicchatreader.service.ChapterRecapChatService;
import com.classicchatreader.service.ChapterRecapService;
import com.classicchatreader.service.RecapMetricsService;
import com.classicchatreader.service.RecapRolloutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChapterRecapController.class)
@TestPropertySource(properties = {
        "generation.cache-only=true",
        "ai.chat.enabled=true"
})
class ChapterRecapControllerCacheOnlyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChapterRecapService chapterRecapService;

    @MockitoBean
    private ChapterRecapChatService chapterRecapChatService;

    @MockitoBean
    private RecapRolloutService recapRolloutService;

    @MockitoBean
    private RecapMetricsService recapMetricsService;

    @Test
    void getStatus_cacheOnlyMode_setsCacheOnlyFlag() throws Exception {
        mockMvc.perform(get("/api/recaps/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheOnly", is(true)));
    }

    @Test
    void requestChapterRecapGeneration_cacheOnlyMode_returnsConflict() throws Exception {
        mockMvc.perform(post("/api/recaps/chapter/chapter-1/generate"))
                .andExpect(status().isConflict());
    }

    @Test
    void requeueStuckRecaps_cacheOnlyMode_returnsConflict() throws Exception {
        mockMvc.perform(post("/api/recaps/book/book-1/requeue-stuck"))
                .andExpect(status().isConflict());
    }

    @Test
    void chat_cacheOnlyMode_allowsChatWhenEnabled() throws Exception {
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(true);
        when(chapterRecapChatService.chat("book-1", "Summarize this chapter", java.util.List.of(), 0))
                .thenReturn("Summary so far.");

        mockMvc.perform(post("/api/recaps/book/book-1/chat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "Summarize this chapter",
                                  "conversationHistory": [],
                                  "readerChapterIndex": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", is("Summary so far.")));
    }
}
