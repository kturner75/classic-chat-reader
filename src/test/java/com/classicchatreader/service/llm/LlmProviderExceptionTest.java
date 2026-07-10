package com.classicchatreader.service.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.SocketException;
import java.net.URI;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.POST;

class LlmProviderExceptionTest {

    @Test
    void isTransient_detectsConnectionResetWrappedInProviderException() {
        SocketException reset = new SocketException("Connection reset");
        WebClientRequestException requestEx = new WebClientRequestException(
                reset,
                POST,
                URI.create("https://api.openai.com/v1/chat/completions"),
                HttpHeaders.EMPTY);
        LlmProviderException wrapped = new LlmProviderException(
                "Failed to generate response from OpenAI", requestEx);

        assertTrue(LlmProviderException.isTransient(wrapped));
        assertTrue(LlmProviderException.isTransient(requestEx));
    }

    @Test
    void isTransient_detectsTimeout() {
        assertTrue(LlmProviderException.isTransient(new TimeoutException("timed out")));
        assertTrue(LlmProviderException.isTransient(
                new LlmProviderException("Failed", new TimeoutException("timed out"))));
    }

    @Test
    void isTransient_falseForOrdinaryFailures() {
        assertFalse(LlmProviderException.isTransient(new LlmProviderException("bad response")));
        assertFalse(LlmProviderException.isTransient(new IllegalArgumentException("bad arg")));
        assertFalse(LlmProviderException.isTransient(
                new LlmProviderException("parse failed", new IllegalStateException("no choices"))));
    }
}
