package com.classicchatreader.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Sends account email through Resend's HTTP API (https://resend.com/docs/api-reference/emails/send-email). */
@Service
public class ResendEmailSender implements TransactionalEmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String from;

    public ResendEmailSender(
            RestClient.Builder restClientBuilder,
            @Value("${email.resend.base-url:https://api.resend.com}") String baseUrl,
            @Value("${email.resend.api-key:}") String apiKey,
            @Value("${email.from:}") String from) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from == null ? "" : from.trim();
        if (!isConfigured()) {
            log.warn("Account email is not configured (email.resend.api-key / email.from); password reset emails will not be sent");
        }
    }

    /** True when a provider key and sender address are configured, so mail can actually leave. */
    public boolean isConfigured() {
        return !apiKey.isEmpty() && !from.isEmpty();
    }

    @Override
    public boolean send(String to, String subject, String textBody, String htmlBody) {
        if (!isConfigured()) {
            log.warn("Skipped account email: email provider is not configured");
            return false;
        }
        try {
            restClient.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", from,
                            "to", List.of(to),
                            "subject", subject,
                            "text", textBody,
                            "html", htmlBody))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            // Only the status: Resend's error body can quote the recipient, which is personal data.
            log.warn("Resend rejected an account email: HTTP {}", e.getStatusCode().value());
            return false;
        } catch (RestClientException e) {
            log.warn("Could not reach Resend to send an account email: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
