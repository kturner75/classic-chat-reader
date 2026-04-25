package com.classicchatreader.service.llm;

/**
 * Options for LLM generation requests.
 */
public record LlmOptions(
    double temperature,
    Double topP,        // nullable
    Integer maxTokens   // nullable
) {
    /**
     * Create options with just temperature.
     */
    public static LlmOptions withTemperature(double temp) {
        return new LlmOptions(temp, null, null);
    }

    /**
     * Create options with temperature and top_p.
     */
    public static LlmOptions withTemperatureAndTopP(double temp, double topP) {
        return new LlmOptions(temp, topP, null);
    }

    /**
     * Create options with all parameters.
     */
    public static LlmOptions full(double temp, double topP, int maxTokens) {
        return new LlmOptions(temp, topP, maxTokens);
    }
}
