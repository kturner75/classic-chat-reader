package com.classicchatreader.config;

import com.classicchatreader.service.llm.LlmProvider;
import com.classicchatreader.service.llm.OllamaLlmProvider;
import com.classicchatreader.service.llm.XaiLlmProvider;
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

    @Value("${ai.reasoning.xai.model:grok-4-1-fast-reasoning}")
    private String reasoningXaiModel;

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

    @Value("${recap.reasoning.xai.model:${ai.reasoning.xai.model:grok-4-1-fast-reasoning}}")
    private String recapReasoningXaiModel;

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

    @Value("${quiz.reasoning.xai.model:${ai.reasoning.xai.model:grok-4-1-fast-reasoning}}")
    private String quizReasoningXaiModel;

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

    @Value("${ai.chat.xai.model:grok-4-1-fast-non-reasoning}")
    private String chatXaiModel;

    @Bean
    @Qualifier("reasoningLlmProvider")
    public LlmProvider reasoningLlmProvider() {
        log.info("Configuring reasoning LLM provider: {}", reasoningProvider);
        return createProvider(
                reasoningProvider,
                reasoningOllamaBaseUrl, reasoningOllamaModel,
                reasoningXaiApiKey, reasoningXaiModel,
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
                chatTimeoutSeconds,
                "chat"
        );
    }

    private LlmProvider createProvider(
            String providerType,
            String ollamaBaseUrl, String ollamaModel,
            String xaiApiKey, String xaiModel,
            int timeoutSeconds,
            String purpose) {

        return switch (providerType.toLowerCase()) {
            case "ollama" -> {
                log.info("Creating Ollama provider for {}: baseUrl={}, model={}",
                        purpose, ollamaBaseUrl, ollamaModel);
                yield new OllamaLlmProvider(ollamaBaseUrl, ollamaModel, timeoutSeconds);
            }
            case "xai" -> {
                if (xaiApiKey == null || xaiApiKey.isBlank()) {
                    log.warn("xAI API key not configured for {} provider, falling back to Ollama", purpose);
                    yield new OllamaLlmProvider(ollamaBaseUrl, ollamaModel, timeoutSeconds);
                }
                log.info("Creating xAI provider for {}: model={}", purpose, xaiModel);
                yield new XaiLlmProvider(xaiApiKey, xaiModel, timeoutSeconds);
            }
            default -> {
                log.warn("Unknown provider type '{}' for {}, falling back to Ollama", providerType, purpose);
                yield new OllamaLlmProvider(ollamaBaseUrl, ollamaModel, timeoutSeconds);
            }
        };
    }
}
