package com.classicchatreader.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Shared WebClient POST helper for LLM providers. Retries once on transient
 * connection drops (idle keep-alive reset, timeout) so a stale pooled socket
 * does not fail an otherwise valid chat turn.
 */
final class LlmWebClientSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmWebClientSupport.class);

    private LlmWebClientSupport() {
    }

    static String postJson(WebClient.RequestBodySpec spec, Object body, Duration timeout, String provider) {
        return spec.contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .retryWhen(Retry.max(1)
                        .filter(LlmProviderException::isRetriableConnectionFailure)
                        .doBeforeRetry(signal -> log.warn(
                                "event=llm_transient_retry provider={} error={}",
                                provider, signal.failure().toString())))
                .block();
    }
}
