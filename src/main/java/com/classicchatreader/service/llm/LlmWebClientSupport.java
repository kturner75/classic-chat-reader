package com.classicchatreader.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Shared WebClient POST helper for LLM providers.
 * xAI/Cloudflare often RST idle keep-alive sockets; new connections avoid that.
 * Connection-reset still retries once (not client timeouts).
 */
final class LlmWebClientSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmWebClientSupport.class);

    private LlmWebClientSupport() {
    }

    static WebClient xaiWebClient(String baseUrl) {
        HttpClient httpClient = HttpClient.create().keepAlive(false).compress(true);
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
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
