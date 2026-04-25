package com.classicchatreader.controller;

import com.classicchatreader.model.ChapterQuizGradeResponse;
import com.classicchatreader.model.ChapterQuizResponse;
import com.classicchatreader.model.ChapterQuizStatusResponse;
import com.classicchatreader.model.ChapterQuizViewPayload;
import com.classicchatreader.model.QuizProgress;
import com.classicchatreader.model.QuizTrophy;
import com.classicchatreader.service.QuizProgressService;
import com.classicchatreader.service.ChapterQuizService;
import com.classicchatreader.service.QuizMetricsService;
import com.classicchatreader.service.ReaderIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

@WebMvcTest(ChapterQuizController.class)
@TestPropertySource(properties = {
        "generation.cache-only=false"
})
class ChapterQuizControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChapterQuizService chapterQuizService;

    @MockitoBean
    private QuizProgressService quizProgressService;

    @MockitoBean
    private QuizMetricsService quizMetricsService;

    @MockitoBean
    private ReaderIdentityService readerIdentityService;

    @Test
    void getStatus_returnsFeatureState() throws Exception {
        when(chapterQuizService.isProviderAvailable()).thenReturn(false);
        when(chapterQuizService.getQueueDepth()).thenReturn(7);
        when(chapterQuizService.isQueueProcessorRunning()).thenReturn(true);
        when(quizMetricsService.snapshot()).thenReturn(Map.of(
                "generationRequested", 9L,
                "generationCompleted", 8L,
                "generationFallbackCompleted", 2L,
                "generationFailed", 1L,
                "generationAverageLatencyMs", 250L,
                "readFailed", 0L,
                "statusReadFailed", 0L
        ));

        mockMvc.perform(get("/api/quizzes/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.reasoningEnabled", is(true)))
                .andExpect(jsonPath("$.cacheOnly", is(false)))
                .andExpect(jsonPath("$.providerAvailable", is(false)))
                .andExpect(jsonPath("$.available", is(true)))
                .andExpect(jsonPath("$.generationAvailable", is(true)))
                .andExpect(jsonPath("$.queueDepth", is(7)))
                .andExpect(jsonPath("$.queueProcessorRunning", is(true)))
                .andExpect(jsonPath("$.metrics.generationCompleted", is(8)))
                .andExpect(jsonPath("$.metrics.generationAverageLatencyMs", is(250)))
                .andExpect(jsonPath("$.metrics.readFailed", is(0)))
                .andExpect(jsonPath("$.metrics.statusReadFailed", is(0)));
    }

    @Test
    void getChapterQuiz_existingChapter_returnsPayload() throws Exception {
        ChapterQuizResponse response = new ChapterQuizResponse(
                "book-1",
                "chapter-1",
                0,
                "Chapter 1",
                "COMPLETED",
                true,
                LocalDateTime.of(2026, 2, 11, 10, 30),
                LocalDateTime.of(2026, 2, 11, 10, 31),
                "v1",
                "grok",
                1,
                new ChapterQuizViewPayload(List.of(
                        new ChapterQuizViewPayload.Question(
                                "What does Holmes examine?",
                                List.of("A clue", "A coin", "A map", "A letter")
                        )
                ))
        );
        when(chapterQuizService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(chapterQuizService.getChapterQuiz("chapter-1")).thenReturn(Optional.of(response));

        mockMvc.perform(get("/api/quizzes/chapter/chapter-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.payload.questions[0].question", is("What does Holmes examine?")));
    }

    @Test
    void getChapterQuiz_whenServiceFails_returnsServerError() throws Exception {
        when(chapterQuizService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(chapterQuizService.getChapterQuiz("chapter-1")).thenThrow(new RuntimeException("db unavailable"));

        mockMvc.perform(get("/api/quizzes/chapter/chapter-1"))
                .andExpect(status().isInternalServerError());

        verify(quizMetricsService).recordReadFailed();
    }

    @Test
    void getChapterQuizStatus_existingChapter_returnsStatus() throws Exception {
        ChapterQuizStatusResponse response = new ChapterQuizStatusResponse(
                "book-1",
                "chapter-1",
                "PENDING",
                false,
                null,
                LocalDateTime.of(2026, 2, 11, 9, 0)
        );
        when(chapterQuizService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(chapterQuizService.getChapterQuizStatus("chapter-1")).thenReturn(Optional.of(response));

        mockMvc.perform(get("/api/quizzes/chapter/chapter-1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookId", is("book-1")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.ready", is(false)));
    }

    @Test
    void getChapterQuizStatus_whenServiceFails_returnsServerError() throws Exception {
        when(chapterQuizService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(chapterQuizService.getChapterQuizStatus("chapter-1")).thenThrow(new RuntimeException("db unavailable"));

        mockMvc.perform(get("/api/quizzes/chapter/chapter-1/status"))
                .andExpect(status().isInternalServerError());

        verify(quizMetricsService).recordStatusReadFailed();
    }

    @Test
    void requestChapterQuizGeneration_existingChapter_returnsAccepted() throws Exception {
        when(chapterQuizService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));

        mockMvc.perform(post("/api/quizzes/chapter/chapter-1/generate"))
                .andExpect(status().isAccepted());

        verify(chapterQuizService).requestChapterQuiz("chapter-1");
    }

    @Test
    void gradeQuiz_returnsScore() throws Exception {
        ChapterQuizGradeResponse graded = new ChapterQuizGradeResponse(
                "book-1",
                "chapter-1",
                3,
                2,
                67,
                1,
                List.of(),
                new QuizProgress(5, 2, 1),
                List.of(
                        new ChapterQuizGradeResponse.QuestionResult(
                                0,
                                "Question 1",
                                1,
                                1,
                                true,
                                "Answer 1",
                                0,
                                "Citation"
                        )
                )
        );
        when(chapterQuizService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(readerIdentityService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(chapterQuizService.gradeQuiz("chapter-1", List.of(1, 0, 2), "reader-1", null))
                .thenReturn(Optional.of(graded));

        mockMvc.perform(post("/api/quizzes/chapter/chapter-1/grade")
                        .contentType("application/json")
                        .content("""
                                {
                                  "selectedOptionIndexes": [1, 0, 2]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scorePercent", is(67)))
                .andExpect(jsonPath("$.correctAnswers", is(2)));
    }

    @Test
    void getBookTrophies_returnsUnlockedTrophies() throws Exception {
        when(readerIdentityService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("reader-1", false, null));
        when(quizProgressService.getBookTrophies("book-1", "reader-1", null)).thenReturn(List.of(
                new QuizTrophy(
                        "quiz_first_attempt",
                        "First Checkpoint",
                        "Complete your first chapter quiz.",
                        LocalDateTime.of(2026, 2, 11, 12, 0)
                )
        ));

        mockMvc.perform(get("/api/quizzes/book/book-1/trophies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code", is("quiz_first_attempt")))
                .andExpect(jsonPath("$[0].title", is("First Checkpoint")));
    }

    @Test
    void getBookTrophies_authenticatedUser_usesUserScopedTrophies() throws Exception {
        when(readerIdentityService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReaderIdentityService.ReaderIdentity("user:user-99", true, "user-99"));
        when(quizProgressService.getBookTrophies("book-1", null, "user-99")).thenReturn(List.of(
                new QuizTrophy(
                        "quiz_first_attempt",
                        "First Checkpoint",
                        "Complete your first chapter quiz.",
                        LocalDateTime.of(2026, 2, 11, 12, 0)
                )
        ));

        mockMvc.perform(get("/api/quizzes/book/book-1/trophies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code", is("quiz_first_attempt")));
    }
}
