package com.classicchatreader.service.llm;

/**
 * Exception thrown when an LLM provider encounters an error.
 */
public class LlmProviderException extends RuntimeException {

    public LlmProviderException(String message) {
        super(message);
    }

    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
