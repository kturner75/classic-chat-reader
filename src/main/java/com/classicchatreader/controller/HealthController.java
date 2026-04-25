package com.classicchatreader.controller;

import jakarta.servlet.http.HttpServletRequest;
import com.classicchatreader.config.RequestCorrelation;
import com.classicchatreader.model.GenerationJobStatusResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final GenerationJobStatusService generationJobStatusService;
    private final AccountAuthService accountAuthService;
    private final AccountMetricsService accountMetricsService;
    private final ChapterQuizService chapterQuizService;
    private final ChapterRecapService chapterRecapService;
    private final ChapterRecapChatService chapterRecapChatService;
    private final CharacterService characterService;
    private final CharacterExtractionService characterExtractionService;
    private final CharacterChatService characterChatService;
    private final IllustrationService illustrationService;
    private final IllustrationStyleAnalysisService illustrationStyleAnalysisService;
    private final ComfyUIService comfyUIService;
    private final TtsService ttsService;
    private final QuizMetricsService quizMetricsService;
    private final RecapMetricsService recapMetricsService;

    public HealthController(
            GenerationJobStatusService generationJobStatusService,
            AccountAuthService accountAuthService,
            AccountMetricsService accountMetricsService,
            ChapterQuizService chapterQuizService,
            ChapterRecapService chapterRecapService,
            ChapterRecapChatService chapterRecapChatService,
            CharacterService characterService,
            CharacterExtractionService characterExtractionService,
            CharacterChatService characterChatService,
            IllustrationService illustrationService,
            IllustrationStyleAnalysisService illustrationStyleAnalysisService,
            ComfyUIService comfyUIService,
            TtsService ttsService,
            QuizMetricsService quizMetricsService,
            RecapMetricsService recapMetricsService) {
        this.generationJobStatusService = generationJobStatusService;
        this.accountAuthService = accountAuthService;
        this.accountMetricsService = accountMetricsService;
        this.chapterQuizService = chapterQuizService;
        this.chapterRecapService = chapterRecapService;
        this.chapterRecapChatService = chapterRecapChatService;
        this.characterService = characterService;
        this.characterExtractionService = characterExtractionService;
        this.characterChatService = characterChatService;
        this.illustrationService = illustrationService;
        this.illustrationStyleAnalysisService = illustrationStyleAnalysisService;
        this.comfyUIService = comfyUIService;
        this.ttsService = ttsService;
        this.quizMetricsService = quizMetricsService;
        this.recapMetricsService = recapMetricsService;
    }

    @GetMapping("/health")
    public Health health() {
        return new Health("ok");
    }

    @GetMapping("/health/details")
    public HealthDetails healthDetails(HttpServletRequest request) {
        GenerationJobStatusResponse generationStatus = generationJobStatusService.getGlobalStatus();
        ProviderHealth providers = new ProviderHealth(
                chapterQuizService.isProviderAvailable(),
                chapterRecapService.isProviderAvailable(),
                chapterRecapChatService.isChatProviderAvailable(),
                characterExtractionService.isReasoningProviderAvailable(),
                characterChatService.isChatProviderAvailable(),
                illustrationStyleAnalysisService.isReasoningProviderAvailable(),
                comfyUIService.isAvailable(),
                ttsService.isConfigured()
        );
        QueueHealth queues = new QueueHealth(
                illustrationService.isQueueProcessorRunning(),
                characterService.isQueueProcessorRunning(),
                chapterRecapService.isQueueProcessorRunning(),
                chapterQuizService.isQueueProcessorRunning(),
                illustrationService.getQueueDepth(),
                characterService.getQueueDepth(),
                chapterRecapService.getQueueDepth(),
                chapterQuizService.getQueueDepth(),
                generationStatus
        );

        boolean queueProcessorsHealthy = queues.illustrationQueueProcessorRunning()
                && queues.characterQueueProcessorRunning()
                && queues.recapQueueProcessorRunning()
                && queues.quizQueueProcessorRunning();

        Map<String, Object> accountMetrics = new LinkedHashMap<>(accountMetricsService.snapshot());
        accountMetrics.put("accountAuthEnabled", accountAuthService.isAccountAuthEnabled());
        accountMetrics.put("rolloutMode", accountAuthService.getRolloutMode());
        accountMetrics.put("accountRequired", accountAuthService.isAccountRequired());

        return new HealthDetails(
                queueProcessorsHealthy ? "ok" : "degraded",
                queueProcessorsHealthy,
                RequestCorrelation.resolveRequestId(request),
                LocalDateTime.now(),
                providers,
                queues,
                quizMetricsService.snapshot(),
                recapMetricsService.snapshot(),
                accountMetrics
        );
    }

    public record Health(String status) {}

    public record HealthDetails(
            String status,
            boolean queueProcessorsHealthy,
            String requestId,
            LocalDateTime asOf,
            ProviderHealth providers,
            QueueHealth queues,
            Map<String, Object> quizMetrics,
            Map<String, Object> recapMetrics,
            Map<String, Object> accountMetrics
    ) {
    }

    public record ProviderHealth(
            boolean quizReasoningAvailable,
            boolean recapReasoningAvailable,
            boolean recapChatAvailable,
            boolean characterReasoningAvailable,
            boolean characterChatAvailable,
            boolean illustrationReasoningAvailable,
            boolean comfyUiAvailable,
            boolean ttsConfigured
    ) {
    }

    public record QueueHealth(
            boolean illustrationQueueProcessorRunning,
            boolean characterQueueProcessorRunning,
            boolean recapQueueProcessorRunning,
            boolean quizQueueProcessorRunning,
            int illustrationQueueDepth,
            int characterQueueDepth,
            int recapQueueDepth,
            int quizQueueDepth,
            GenerationJobStatusResponse generation
    ) {
    }
}
