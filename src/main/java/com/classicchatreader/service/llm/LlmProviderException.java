package com.classicchatreader.service.llm;

import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

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

    /**
     * True for expected intermittent network/timeout failures (connection reset,
     * refused, DNS blips, client timeouts). Callers can log these at WARN without
     * a stack trace to avoid cluttering logs.
     */
    public static boolean isTransient(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof WebClientRequestException
                    || current instanceof SocketException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof IOException) {
                String message = current.getMessage();
                if (message != null) {
                    String lower = message.toLowerCase();
                    if (lower.contains("connection reset")
                            || lower.contains("broken pipe")
                            || lower.contains("connection refused")
                            || lower.contains("timed out")
                            || lower.contains("timeout")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
