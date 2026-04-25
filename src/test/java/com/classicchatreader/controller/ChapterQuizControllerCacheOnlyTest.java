package com.classicchatreader.controller;

import com.classicchatreader.service.ChapterQuizService;
import com.classicchatreader.service.QuizMetricsService;
import com.classicchatreader.service.QuizProgressService;
import com.classicchatreader.service.ReaderIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChapterQuizController.class)
@TestPropertySource(properties = {
        "generation.cache-only=true"
})
class ChapterQuizControllerCacheOnlyTest {

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
    void getStatus_cacheOnlyMode_marksQuizUnavailable() throws Exception {
        when(chapterQuizService.isProviderAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/quizzes/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheOnly", is(true)))
                .andExpect(jsonPath("$.available", is(true)))
                .andExpect(jsonPath("$.generationAvailable", is(false)));
    }

    @Test
    void requestChapterQuizGeneration_cacheOnlyMode_returnsConflict() throws Exception {
        mockMvc.perform(post("/api/quizzes/chapter/chapter-1/generate"))
                .andExpect(status().isConflict());

        verifyNoInteractions(chapterQuizService);
    }

    @Test
    void getChapterQuiz_cacheOnlyMode_allowsCachedReads() throws Exception {
        when(chapterQuizService.findBookIdForChapter("chapter-1")).thenReturn(Optional.of("book-1"));
        when(chapterQuizService.getChapterQuiz("chapter-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/quizzes/chapter/chapter-1"))
                .andExpect(status().isNotFound());
    }
}
