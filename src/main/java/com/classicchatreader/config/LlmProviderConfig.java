package com.classicchatreader.config;

import com.classicchatreader.service.llm.LlmProvider;
import com.classicchatreader.service.llm.OllamaLlmProvider;
import com.classicchatreader.service.llm.OpenAiLlmProvider;
import com.classicchatreader.service.llm.XaiLlmProvider;
import com.classicchatreader.service.llm.XaiOAuthTokenManager;
import com.classicchatreader.service.llm.XaiRealtimeSessionService;
import com.classicchatreader.service.llm.XaiVoiceCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for LLM providers.
 * Creates separate beans for reasoning and chat tasks.
 */
@Configuration
public class LlmProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfig.class);

    // Reasoning provider config
    @Value("${ai.reasoning.provider:ollama}")
    private String reasoningProvider;

    @Value("${ai.reasoning.timeout-seconds:180}")
    private int reasoningTimeoutSeconds;

    @Value("${ai.reasoning.ollama.base-url:http://localhost:11434}")
    private String reasoningOllamaBaseUrl;

    @Value("${ai.reasoning.ollama.model:llama3.1:latest}")
    private String reasoningOllamaModel;

    @Value("${ai.reasoning.xai.api-key:}")
    private String reasoningXaiApiKey;

    @Value("${ai.reasoning.xai.model:grok-4.20-reasoning}")
    private String reasoningXaiModel;

    @Value("${ai.reasoning.openai.base-url:https://api.openai.com/v1}")
    private String reasoningOpenAiBaseUrl;

    @Value("${ai.reasoning.openai.api-key:${OPENAI_API_KEY:}}")
    private String reasoningOpenAiApiKey;

    @Value("${ai.reasoning.openai.model:gpt-5.5}")
    private String reasoningOpenAiModel;

    // Recap reasoning provider config (can differ from global reasoning provider)
    @Value("${recap.reasoning.provider:${ai.reasoning.provider:ollama}}")
    private String recapReasoningProvider;

    @Value("${recap.reasoning.timeout-seconds:${ai.reasoning.timeout-seconds:180}}")
    private int recapReasoningTimeoutSeconds;

    @Value("${recap.reasoning.ollama.base-url:${ai.reasoning.ollama.base-url:http://localhost:11434}}")
    private String recapReasoningOllamaBaseUrl;

    @Value("${recap.reasoning.ollama.model:${ai.reasoning.ollama.model:llama3.1:latest}}")
    private String recapReasoningOllamaModel;

    @Value("${recap.reasoning.xai.api-key:${ai.reasoning.xai.api-key:}}")
    private String recapReasoningXaiApiKey;

    @Value("${recap.reasoning.xai.model:${ai.reasoning.xai.model:grok-4.20-reasoning}}")
    private String recapReasoningXaiModel;

    @Value("${recap.reasoning.openai.base-url:${ai.reasoning.openai.base-url:https://api.openai.com/v1}}")
    private String recapReasoningOpenAiBaseUrl;

    @Value("${recap.reasoning.openai.api-key:${ai.reasoning.openai.api-key:${OPENAI_API_KEY:}}}")
    private String recapReasoningOpenAiApiKey;

    @Value("${recap.reasoning.openai.model:${ai.reasoning.openai.model:gpt-5.5}}")
    private String recapReasoningOpenAiModel;

    // Quiz reasoning provider config (defaults to global reasoning provider)
    @Value("${quiz.reasoning.provider:${ai.reasoning.provider:ollama}}")
    private String quizReasoningProvider;

    @Value("${quiz.reasoning.timeout-seconds:${ai.reasoning.timeout-seconds:180}}")
    private int quizReasoningTimeoutSeconds;

    @Value("${quiz.reasoning.ollama.base-url:${ai.reasoning.ollama.base-url:http://localhost:11434}}")
    private String quizReasoningOllamaBaseUrl;

    @Value("${quiz.reasoning.ollama.model:${ai.reasoning.ollama.model:llama3.1:latest}}")
    private String quizReasoningOllamaModel;

    @Value("${quiz.reasoning.xai.api-key:${ai.reasoning.xai.api-key:}}")
    private String quizReasoningXaiApiKey;

    @Value("${quiz.reasoning.xai.model:${ai.reasoning.xai.model:grok-4.20-reasoning}}")
    private String quizReasoningXaiModel;

    @Value("${quiz.reasoning.openai.base-url:${ai.reasoning.openai.base-url:https://api.openai.com/v1}}")
    private String quizReasoningOpenAiBaseUrl;

    @Value("${quiz.reasoning.openai.api-key:${ai.reasoning.openai.api-key:${OPENAI_API_KEY:}}}")
    private String quizReasoningOpenAiApiKey;

    @Value("${quiz.reasoning.openai.model:${ai.reasoning.openai.model:gpt-5.5}}")
    private String quizReasoningOpenAiModel;

    // Chat provider config
    @Value("${ai.chat.provider:xai}")
    private String chatProvider;

    @Value("${ai.chat.timeout-seconds:60}")
    private int chatTimeoutSeconds;

    @Value("${ai.chat.ollama.base-url:http://localhost:11434}")
    private String chatOllamaBaseUrl;

    @Value("${ai.chat.ollama.model:llama3.1:latest}")
    private String chatOllamaModel;

    @Value("${ai.chat.xai.api-key:}")
    private String chatXaiApiKey;

    @Value("${ai.chat.xai.model:grok-4.20-non-reasoning}")
    private String chatXaiModel;

    @Value("${ai.chat.openai.base-url:https://api.openai.com/v1}")
    private String chatOpenAiBaseUrl;

    @Value("${ai.chat.openai.api-key:${OPENAI_API_KEY:}}")
    private String chatOpenAiApiKey;

    @Value("${ai.chat.openai.model:gpt-5.5}")
    private String chatOpenAiModel;

    // xAI OAuth (SuperGrok subscription) config - shared across all xAI-backed providers.
    // Refresh token is provisioned manually via scripts/xai-oauth-login.sh; when absent,
    // providers behave exactly as before and use their configured API key.
    @Value("${ai.xai.oauth.enabled:true}")
    private boolean xaiOAuthEnabled;

    @Value("${ai.xai.oauth.refresh-token:}")
    private String xaiOAuthRefreshToken;

    // xAI rotates refresh tokens on every use; the newest one is persisted here so it
    // survives process restarts instead of retrying the now-invalidated configured value.
    @Value("${ai.xai.oauth.refresh-token-file:./data/xai-oauth-refresh-token}")
    private String xaiOAuthRefreshTokenFile;

    // Voice call (xAI Realtime) config - independent of ai.chat.provider so voice
    // keeps using xAI even when text chat runs on OpenAI/Ollama.
    @Value("${voice.call.xai.api-key:${ai.chat.xai.api-key:}}")
    private String voiceCallXaiApiKey;

    @Value("${voice.call.xai.model:grok-voice-think-fast-2.0}")
    private String voiceCallXaiModel;

    @Value("${voice.call.token-ttl-seconds:1800}")
    private int voiceCallTokenTtlSeconds;

    @Value("${voice.call.timeout-seconds:15}")
    private int voiceCallTimeoutSeconds;

    @Value("${voice.call.xai.voices-url:https://api.x.ai/v1/tts/voices}")
    private String voiceCatalogUrl;

    @Value("${voice.call.voice-catalog.timeout-seconds:10}")
    private int voiceCatalogTimeoutSeconds;

    @Value("${voice.call.voice-catalog.cache-ttl-minutes:1440}")
    private int voiceCatalogCacheTtlMinutes;

    @Bean
    public XaiOAuthTokenManager xaiOAuthTokenManager() {
        return new XaiOAuthTokenManager(xaiOAuthRefreshToken, xaiOAuthEnabled, xaiOAuthRefreshTokenFile);
    }

    @Bean
    public XaiRealtimeSessionService xaiRealtimeSessionService() {
        return new XaiRealtimeSessionService(voiceCallXaiApiKey, voiceCallXaiModel,
                voiceCallTokenTtlSeconds, voiceCallTimeoutSeconds, xaiOAuthTokenManager());
    }

    @Bean
    public XaiVoiceCatalogService xaiVoiceCatalogService() {
        return new XaiVoiceCatalogService(voiceCallXaiApiKey, voiceCatalogUrl,
                voiceCatalogTimeoutSeconds, voiceCatalogCacheTtlMinutes, xaiOAuthTokenManager());
    }

    @Bean
    @Qualifier("reasoningLlmProvider")
    public LlmProvider reasoningLlmProvider() {
        log.info("Configuring reasoning LLM provider: {}", reasoningProvider);
        return createProvider(
                reasoningProvider,
                reasoningOllamaBaseUrl, reasoningOllamaModel,
                reasoningXaiApiKey, reasoningXaiModel,
                reasoningOpenAiBaseUrl, reasoningOpenAiApiKey, reasoningOpenAiModel,
                reasoningTimeoutSeconds,
                "reasoning"
        );
    }

    @Bean
    @Qualifier("recapReasoningLlmProvider")
    public LlmProvider recapReasoningLlmProvider() {
        log.info("Configuring recap reasoning LLM provider: {}", recapReasoningProvider);
        return createProvider(
                recapReasoningProvider,
                recapReasoningOllamaBaseUrl, recapReasoningOllamaModel,
                recapReasoningXaiApiKey, recapReasoningXaiModel,
                recapReasoningOpenAiBaseUrl, recapReasoningOpenAiApiKey, recapReasoningOpenAiModel,
                recapReasoningTimeoutSeconds,
                "recap-reasoning"
        );
    }

    @Bean
    @Qualifier("quizReasoningLlmProvider")
    public LlmProvider quizReasoningLlmProvider() {
        log.info("Configuring quiz reasoning LLM provider: {}", quizReasoningProvider);
        return createProvider(
                quizReasoningProvider,
                quizReasoningOllamaBaseUrl, quizReasoningOllamaModel,
                quizReasoningXaiApiKey, quizReasoningXaiModel,
                quizReasoningOpenAiBaseUrl, quizReasoningOpenAiApiKey, quizReasoningOpenAiModel,
                quizReasoningTimeoutSeconds,
                "quiz-reasoning"
        );
    }

    @Bean
    @Qualifier("chatLlmProvider")
    public LlmProvider chatLlmProvider() {
        log.info("Configuring chat LLM provider: {}", chatProvider);
        return createProvider(
                chatProvider,
                chatOllamaBaseUrl, chatOllamaModel,
                chatXaiApiKey, chatXaiModel,
                chatOpenAiBaseUrl, chatOpenAiApiKey, chatOpenAiModel,
                chatTimeoutSeconds,
                "chat"
        );
    }

    private LlmProvider createProvider(
            String providerType,
            String ollamaBaseUrl, String ollamaModel,
            String xaiApiKey, String xaiModel,
            String openAiBaseUrl, String openAiApiKey, String openAiModel,
            int timeoutSeconds,
            String purpose) {

        return switch (providerType.toLowerCase()) {
            case "ollama" -> {
                log.info("Creating Ollama provider for {}: baseUrl={}, model={}",
                        purpose, ollamaBaseUrl, ollamaModel);
                yield new OllamaLlmProvider(ollamaBaseUrl, ollamaModel, timeoutSeconds);
            }
            case "xai" -> {
                XaiOAuthTokenManager oauthTokenManager = xaiOAuthTokenManager();
                if ((xaiApiKey == null || xaiApiKey.isBlank()) && !oauthTokenManager.isConfigured()) {
                    log.warn("Neither xAI OAuth nor API key configured for {} provider, falling back to Ollama", purpose);
                    yield new OllamaLlmProvider(ollamaBaseUrl, ollamaModel, timeoutSeconds);
                }
                log.info("Creating xAI provider for {}: model={}, oauth={}",
                        purpose, xaiModel, oauthTokenManager.isConfigured());
                yield new XaiLlmProvider(xaiApiKey, xaiModel, timeoutSeconds, oauthTokenManager);
            }
            case "openai" -> {
                if (openAiApiKey == null || openAiApiKey.isBlank()) {
                    log.warn("OpenAI API key not configured for {} provider, falling back to Ollama", purpose);
                    yield new OllamaLlmProvider(ollamaBaseUrl, ollamaModel, timeoutSeconds);
                }
                log.info("Creating OpenAI provider for {}: model={}", purpose, openAiModel);
                yield new OpenAiLlmProvider(openAiBaseUrl, openAiApiKey, openAiModel, timeoutSeconds);
            }
            default -> {
                log.warn("Unknown provider type '{}' for {}, falling back to Ollama", providerType, purpose);
                yield new OllamaLlmProvider(ollamaBaseUrl, ollamaModel, timeoutSeconds);
            }
        };
    }
}
