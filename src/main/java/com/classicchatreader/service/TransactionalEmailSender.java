package com.classicchatreader.service;

/**
 * Sends one-off account email (password reset). The Resend implementation is the only one;
 * the interface exists so tests can capture outgoing mail.
 */
public interface TransactionalEmailSender {

    /** True when a provider key and sender address are configured, so mail can actually leave. */
    boolean isConfigured();

    /** Sends one message. Returns false (and logs) on any provider failure; never throws. */
    boolean send(String to, String subject, String textBody, String htmlBody);
}
