package com.classicchatreader.controller;

import com.classicchatreader.model.ChapterRecapPayload;
import com.classicchatreader.model.ChapterRecapResponse;
import com.classicchatreader.model.ChapterRecapStatusResponse;
import com.classicchatreader.model.ChatMessage;
import com.classicchatreader.service.ChapterRecapChatService;
import com.classicchatreader.service.ChapterRecapService;
import com.classicchatreader.service.RecapMetricsService;
import com.classicchatreader.service.RecapRolloutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChapterRecapController.class)
@TestPropertySource(properties = {
        "generation.cache-only=false",
        "ai.chat.enabled=true"
})
class ChapterRecapControllerTest {

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
    void getStatus_returnsFeatureState() throws Exception {
        when(recapMetricsService.snapshot()).thenReturn(Map.of(
                "readFailed", 0L,
                "statusReadFailed", 0L
        ));

        mockMvc.perform(get("/api/recaps/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.reasoningEnabled", is(true)))
                .andExpect(jsonPath("$.chatEnabled", is(true)))
                .andExpect(jsonPath("$.cacheOnly", is(false)))
                .andExpect(jsonPath("$.chatProviderAvailable", is(false)))
                .andExpect(jsonPath("$.available", is(true)))
                .andExpect(jsonPath("$.metrics.readFailed", is(0)))
                .andExpect(jsonPath("$.metrics.statusReadFailed", is(0)));
    }

    @Test
    void getBookStatus_rolloutDisabled_returnsUnavailable() throws Exception {
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(false);

        mockMvc.perform(get("/api/recaps/book/book-1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolloutAllowed", is(false)))
                .andExpect(jsonPath("$.available", is(false)));
    }

    @Test
    void getChapterRecap_existingChapter_returnsPayload() throws Exception {
        ChapterRecapResponse response = new ChapterRecapResponse(
                "book-1",
                "chapter-1",
                0,
                "Chapter 1",
                "COMPLETED",
                true,
                LocalDateTime.of(2026, 2, 8, 10, 30),
                LocalDateTime.of(2026, 2, 8, 10, 31),
                "v1",
                "grok",
                new ChapterRecapPayload(
                        "A short summary",
                        List.of("Event one", "Event two"),
                        List.of(new ChapterRecapPayload.CharacterDelta("Elizabeth Bennet", "Becomes more skeptical"))
                )
        );
        when(chapterRecapService.getChapterRecap("chapter-1")).thenReturn(Optional.of(response));
        when(chapterRecapService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(true);

        mockMvc.perform(get("/api/recaps/chapter/chapter-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.chapterId", is("chapter-1")))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.ready", is(true)))
                .andExpect(jsonPath("$.payload.shortSummary", is("A short summary")))
                .andExpect(jsonPath("$.payload.keyEvents[0]", is("Event one")))
                .andExpect(jsonPath("$.payload.characterDeltas[0].characterName", is("Elizabeth Bennet")));
    }

    @Test
    void getChapterRecap_missingChapter_returns404() throws Exception {
        when(chapterRecapService.findBookIdForChapter("missing")).thenReturn(Optional.of("book-1"));
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(true);
        when(chapterRecapService.getChapterRecap("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/recaps/chapter/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getChapterRecap_whenServiceFails_returnsServerError() throws Exception {
        when(chapterRecapService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(true);
        when(chapterRecapService.getChapterRecap("chapter-1")).thenThrow(new RuntimeException("db unavailable"));

        mockMvc.perform(get("/api/recaps/chapter/chapter-1"))
                .andExpect(status().isInternalServerError());

        verify(recapMetricsService).recordReadFailed();
    }

    @Test
    void getChapterRecapStatus_existingChapter_returnsStatus() throws Exception {
        ChapterRecapStatusResponse response = new ChapterRecapStatusResponse(
                "book-1",
                "chapter-1",
                "PENDING",
                false,
                null,
                LocalDateTime.of(2026, 2, 8, 9, 0)
        );
        when(chapterRecapService.getChapterRecapStatus("chapter-1")).thenReturn(Optional.of(response));
        when(chapterRecapService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(true);

        mockMvc.perform(get("/api/recaps/chapter/chapter-1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.chapterId", is("chapter-1")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.ready", is(false)));
    }

    @Test
    void getChapterRecapStatus_whenServiceFails_returnsServerError() throws Exception {
        when(chapterRecapService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(true);
        when(chapterRecapService.getChapterRecapStatus("chapter-1")).thenThrow(new RuntimeException("db unavailable"));

        mockMvc.perform(get("/api/recaps/chapter/chapter-1/status"))
                .andExpect(status().isInternalServerError());

        verify(recapMetricsService).recordStatusReadFailed();
    }

    @Test
    void requestChapterRecapGeneration_existingChapter_returnsAccepted() throws Exception {
        when(chapterRecapService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        mockMvc.perform(post("/api/recaps/chapter/chapter-1/generate"))
                .andExpect(status().isAccepted());
        verify(chapterRecapService).requestChapterRecap("chapter-1");
    }

    @Test
    void requestChapterRecapGeneration_rolloutDisabled_stillReturnsAccepted() throws Exception {
        when(chapterRecapService.findBookIdForChapter("chapter-2")).thenReturn(Optional.of("book-1"));
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(false);

        mockMvc.perform(post("/api/recaps/chapter/chapter-2/generate"))
                .andExpect(status().isAccepted());

        verify(chapterRecapService).requestChapterRecap("chapter-2");
    }

    @Test
    void requestChapterRecapGeneration_missingChapter_returns404() throws Exception {
        when(chapterRecapService.findBookIdForChapter("missing")).thenReturn(Optional.of("book-1"));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Chapter not found"))
                .when(chapterRecapService).requestChapterRecap("missing");

        mockMvc.perform(post("/api/recaps/chapter/missing/generate"))
                .andExpect(status().isNotFound());
    }

    @Test
    void requeueStuckRecaps_returnsAcceptedWithCount() throws Exception {
        when(chapterRecapService.resetAndRequeueStuckForBook("book-1")).thenReturn(3);

        mockMvc.perform(post("/api/recaps/book/book-1/requeue-stuck"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.requeued", is(3)));

        verify(chapterRecapService).resetAndRequeueStuckForBook("book-1");
    }

    @Test
    void chat_returnsResponse() throws Exception {
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(true);
        when(chapterRecapChatService.chat("book-1", "What happened?", List.of(), 2))
                .thenReturn("The chapter resolves the conflict.");

        mockMvc.perform(post("/api/recaps/book/book-1/chat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "message": "What happened?",
                                  "conversationHistory": [],
                                  "readerChapterIndex": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.response", is("The chapter resolves the conflict.")));
    }

    @Test
    void analytics_viewed_returnsAccepted() throws Exception {
        when(recapRolloutService.isBookAllowed("book-1")).thenReturn(true);

        mockMvc.perform(post("/api/recaps/analytics")
                        .contentType("application/json")
                        .content("""
                                {
                                  "bookId": "book-1",
                                  "chapterId": "chapter-1",
                                  "event": "viewed"
                                }
                                """))
                .andExpect(status().isAccepted());

        verify(recapMetricsService).recordModalViewed();
    }
}
