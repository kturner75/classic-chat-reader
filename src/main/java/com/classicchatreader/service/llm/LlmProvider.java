package com.classicchatreader.service.llm;

/**
 * Abstraction for LLM providers (OpenAI, Ollama, xAI, etc.)
 */
public interface LlmProvider {

    /**
     * Generate a response from the LLM.
     *
     * @param prompt the prompt to send
     * @param options generation options (temperature, etc.)
     * @return the generated text response
     */
    String generate(String prompt, LlmOptions options);

    /**
     * Check if this provider is available and properly configured.
     *
     * @return true if the provider can accept requests
     */
    boolean isAvailable();

    /**
     * Get the name of this provider for logging/debugging.
     *
     * @return provider name (e.g., "openai", "ollama", "xai")
     */
    String getProviderName();
}
