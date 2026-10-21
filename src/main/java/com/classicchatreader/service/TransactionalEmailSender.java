package com.classicchatreader.service;

/**
 * Sends one-off account email (password reset). The Resend implementation is the only one;
 * the interface exists so tests can capture outgoing mail.
 */
public interface TransactionalEmailSender {

    /** Sends one message. Returns false (and logs) on any provider failure; never throws. */
    boolean send(String to, String subject, String textBody, String htmlBody);
}
