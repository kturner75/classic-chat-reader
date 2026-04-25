package com.classicchatreader.controller;

import com.classicchatreader.model.GenerationJobStatusResponse;
import com.classicchatreader.model.GenerationPipelineStatus;
import com.classicchatreader.service.AccountAuthService;
import com.classicchatreader.service.AccountMetricsService;
import com.classicchatreader.service.ChapterQuizService;
import com.classicchatreader.service.ChapterRecapChatService;
import com.classicchatreader.service.ChapterRecapService;
import com.classicchatreader.service.CharacterChatService;
import com.classicchatreader.service.CharacterExtractionService;
import com.classicchatreader.service.CharacterService;
import com.classicchatreader.service.ComfyUIService;
import com.classicchatreader.service.GenerationJobStatusService;
import com.classicchatreader.service.IllustrationService;
import com.classicchatreader.service.IllustrationStyleAnalysisService;
import com.classicchatreader.service.QuizMetricsService;
import com.classicchatreader.service.RecapMetricsService;
import com.classicchatreader.service.TtsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerationJobStatusService generationJobStatusService;

    @MockitoBean
    private AccountAuthService accountAuthService;

    @MockitoBean
    private AccountMetricsService accountMetricsService;

    @MockitoBean
    private ChapterQuizService chapterQuizService;

    @MockitoBean
    private ChapterRecapService chapterRecapService;

    @MockitoBean
    private ChapterRecapChatService chapterRecapChatService;

    @MockitoBean
    private CharacterService characterService;

    @MockitoBean
    private CharacterExtractionService characterExtractionService;

    @MockitoBean
    private CharacterChatService characterChatService;

    @MockitoBean
    private IllustrationService illustrationService;

    @MockitoBean
    private IllustrationStyleAnalysisService illustrationStyleAnalysisService;

    @MockitoBean
    private ComfyUIService comfyUIService;

    @MockitoBean
    private TtsService ttsService;

    @MockitoBean
    private QuizMetricsService quizMetricsService;

    @MockitoBean
    private RecapMetricsService recapMetricsService;

    @Test
    void health_returnsBasicStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));
    }

    @Test
    void healthDetails_whenQueuesHealthy_returnsOkSnapshot() throws Exception {
        when(generationJobStatusService.getGlobalStatus()).thenReturn(sampleGenerationStatus());
        when(chapterQuizService.isProviderAvailable()).thenReturn(true);
        when(chapterRecapService.isProviderAvailable()).thenReturn(true);
        when(chapterRecapChatService.isChatProviderAvailable()).thenReturn(true);
        when(characterExtractionService.isReasoningProviderAvailable()).thenReturn(true);
        when(characterChatService.isChatProviderAvailable()).thenReturn(true);
        when(illustrationStyleAnalysisService.isReasoningProviderAvailable()).thenReturn(true);
        when(comfyUIService.isAvailable()).thenReturn(true);
        when(ttsService.isConfigured()).thenReturn(true);
        when(illustrationService.isQueueProcessorRunning()).thenReturn(true);
        when(characterService.isQueueProcessorRunning()).thenReturn(true);
        when(chapterRecapService.isQueueProcessorRunning()).thenReturn(true);
        when(chapterQuizService.isQueueProcessorRunning()).thenReturn(true);
        when(illustrationService.getQueueDepth()).thenReturn(1);
        when(characterService.getQueueDepth()).thenReturn(2);
        when(chapterRecapService.getQueueDepth()).thenReturn(3);
        when(chapterQuizService.getQueueDepth()).thenReturn(4);
        when(quizMetricsService.snapshot()).thenReturn(Map.of("readFailed", 0L, "statusReadFailed", 0L));
        when(recapMetricsService.snapshot()).thenReturn(Map.of("readFailed", 1L, "statusReadFailed", 2L));
        when(accountMetricsService.snapshot()).thenReturn(Map.of("claimSyncSucceeded", 5L));
        when(accountAuthService.isAccountAuthEnabled()).thenReturn(true);
        when(accountAuthService.getRolloutMode()).thenReturn("internal");
        when(accountAuthService.isAccountRequired()).thenReturn(false);

        mockMvc.perform(get("/health/details"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status", is("ok")))
                .andExpect(jsonPath("$.queueProcessorsHealthy", is(true)))
                .andExpect(jsonPath("$.providers.quizReasoningAvailable", is(true)))
                .andExpect(jsonPath("$.providers.recapReasoningAvailable", is(true)))
                .andExpect(jsonPath("$.providers.comfyUiAvailable", is(true)))
                .andExpect(jsonPath("$.queues.quizQueueDepth", is(4)))
                .andExpect(jsonPath("$.queues.generation.scope", is("global")))
                .andExpect(jsonPath("$.quizMetrics.readFailed", is(0)))
                .andExpect(jsonPath("$.recapMetrics.statusReadFailed", is(2)))
                .andExpect(jsonPath("$.accountMetrics.claimSyncSucceeded", is(5)))
                .andExpect(jsonPath("$.accountMetrics.rolloutMode", is("internal")));
    }

    @Test
    void healthDetails_whenAnyQueueProcessorDown_returnsDegraded() throws Exception {
        when(generationJobStatusService.getGlobalStatus()).thenReturn(sampleGenerationStatus());
        when(chapterQuizService.isProviderAvailable()).thenReturn(true);
        when(chapterRecapService.isProviderAvailable()).thenReturn(true);
        when(chapterRecapChatService.isChatProviderAvailable()).thenReturn(true);
        when(characterExtractionService.isReasoningProviderAvailable()).thenReturn(true);
        when(characterChatService.isChatProviderAvailable()).thenReturn(true);
        when(illustrationStyleAnalysisService.isReasoningProviderAvailable()).thenReturn(true);
        when(comfyUIService.isAvailable()).thenReturn(true);
        when(ttsService.isConfigured()).thenReturn(true);
        when(illustrationService.isQueueProcessorRunning()).thenReturn(true);
        when(characterService.isQueueProcessorRunning()).thenReturn(false);
        when(chapterRecapService.isQueueProcessorRunning()).thenReturn(true);
        when(chapterQuizService.isQueueProcessorRunning()).thenReturn(true);
        when(illustrationService.getQueueDepth()).thenReturn(0);
        when(characterService.getQueueDepth()).thenReturn(0);
        when(chapterRecapService.getQueueDepth()).thenReturn(0);
        when(chapterQuizService.getQueueDepth()).thenReturn(0);
        when(quizMetricsService.snapshot()).thenReturn(Map.of("readFailed", 0L, "statusReadFailed", 0L));
        when(recapMetricsService.snapshot()).thenReturn(Map.of("readFailed", 0L, "statusReadFailed", 0L));
        when(accountMetricsService.snapshot()).thenReturn(Map.of());
        when(accountAuthService.isAccountAuthEnabled()).thenReturn(true);
        when(accountAuthService.getRolloutMode()).thenReturn("optional");
        when(accountAuthService.isAccountRequired()).thenReturn(false);

        mockMvc.perform(get("/health/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("degraded")))
                .andExpect(jsonPath("$.queueProcessorsHealthy", is(false)))
                .andExpect(jsonPath("$.queues.characterQueueProcessorRunning", is(false)));
    }

    private GenerationJobStatusResponse sampleGenerationStatus() {
        GenerationPipelineStatus sample = GenerationPipelineStatus.of(1, 1, 1, 1, 1);
        return new GenerationJobStatusResponse(
                "global",
                null,
                LocalDateTime.of(2026, 2, 15, 10, 0),
                sample,
                sample,
                sample,
                sample,
                GenerationPipelineStatus.of(4, 4, 4, 4, 4)
        );
    }
}
