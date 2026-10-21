package com.classicchatreader.service;

import com.classicchatreader.entity.PasswordResetTokenEntity;
import com.classicchatreader.entity.UserEntity;
import com.classicchatreader.repository.PasswordResetTokenRepository;
import com.classicchatreader.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Forgot-password flow: email a single-use link, then let its holder set a new password.
 *
 * <p>Requesting a reset returns the same response whether or not the address has an account.
 * The email is sent off the request thread so a real send does not make known addresses
 * noticeably slower; a known address still does a little extra database work, which the
 * per-email and per-IP rate limits keep from being useful for enumeration.
 *
 * <p>The token travels in the URL fragment ({@code /#password_reset=...}), which browsers never
 * send to the server or put in a Referer header, so it stays out of access and proxy logs.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    /** One email per account per cooldown, so the form cannot be used to flood someone's inbox. */
    private static final int RESEND_COOLDOWN_SECONDS = 120;
    static final String RESET_FRAGMENT_PARAM = "password_reset";

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final AccountAuthService accountAuthService;
    private final TransactionalEmailSender emailSender;
    private final String publicBaseUrl;
    private final int tokenTtlMinutes;
    private final ExecutorService mailExecutor;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository,
            AccountAuthService accountAuthService,
            TransactionalEmailSender emailSender,
            @Value("${account.auth.password-reset.public-base-url:http://localhost:8080}") String publicBaseUrl,
            @Value("${account.auth.password-reset.ttl-minutes:30}") int tokenTtlMinutes) {
        this(tokenRepository, userRepository, accountAuthService, emailSender, publicBaseUrl, tokenTtlMinutes,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "password-reset-mail");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    // Visible for testing: a direct executor makes the send synchronous (still after commit).
    PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository,
            AccountAuthService accountAuthService,
            TransactionalEmailSender emailSender,
            String publicBaseUrl,
            int tokenTtlMinutes,
            ExecutorService mailExecutor) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.accountAuthService = accountAuthService;
        this.emailSender = emailSender;
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.tokenTtlMinutes = Math.max(5, Math.min(120, tokenTtlMinutes));
        this.mailExecutor = mailExecutor;
    }

    @PreDestroy
    void shutdown() {
        mailExecutor.shutdown();
    }

    /** Issues a reset link for this address if it has an account. Silent either way. */
    @Transactional
    public void requestReset(String email) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.deleteByExpiresAtBefore(now);

        Optional<String> eligibleEmail = accountAuthService.eligibleAccountEmail(email);
        if (eligibleEmail.isEmpty()) {
            return;
        }
        Optional<UserEntity> user = userRepository.findByEmail(eligibleEmail.get());
        if (user.isEmpty()) {
            return;
        }
        String userId = user.get().getId();
        if (tokenRepository.existsByUserIdAndCreatedAtAfter(userId, now.minusSeconds(RESEND_COOLDOWN_SECONDS))) {
            return;
        }

        // Only the newest link works.
        tokenRepository.deleteByUserId(userId);
        String token = newToken();
        PasswordResetTokenEntity entity = new PasswordResetTokenEntity();
        entity.setTokenHash(hashToken(token));
        entity.setUserId(userId);
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plusMinutes(tokenTtlMinutes));
        tokenRepository.save(entity);

        String recipient = user.get().getEmail();
        String link = publicBaseUrl + "/#" + RESET_FRAGMENT_PARAM + "=" + token;
        afterCommit(() -> mailExecutor.execute(() -> sendResetEmail(recipient, link)));
    }

    /** True while the link can still be used, so the page can say "expired" before asking for a password. */
    @Transactional(readOnly = true)
    public boolean isTokenValid(String token) {
        return findLiveToken(token).isPresent();
    }

    /**
     * Spends the link: sets the new password and signs this browser in. The token row is deleted
     * first and only the request that actually deleted it goes on, so two simultaneous submits of
     * one link cannot both succeed. A rejected password rolls the delete back, keeping the link usable.
     */
    @Transactional
    public AccountAuthService.AuthResult completeReset(String token, String newPassword, HttpServletResponse response) {
        Optional<PasswordResetTokenEntity> live = findLiveToken(token);
        if (live.isEmpty() || tokenRepository.claimByTokenHash(live.get().getTokenHash()) != 1) {
            return AccountAuthService.AuthResult.error(
                    AccountAuthService.ResultStatus.INVALID_CREDENTIALS,
                    accountAuthService.isAccountAuthEnabled(),
                    "This reset link is invalid or has expired. Request a new one.");
        }
        AccountAuthService.AuthResult result =
                accountAuthService.resetPassword(live.get().getUserId(), newPassword, response);
        if (result.status() != AccountAuthService.ResultStatus.SUCCESS) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return result;
    }

    private Optional<PasswordResetTokenEntity> findLiveToken(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            return Optional.empty();
        }
        return tokenRepository.findByTokenHash(hashToken(token.trim()))
                .filter(entity -> entity.getExpiresAt() != null && entity.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private void sendResetEmail(String recipient, String link) {
        String subject = "Reset your Classic Chat Reader password";
        String text = "Someone asked to reset the password for your Classic Chat Reader account.\n\n"
                + "To choose a new password, open this link within " + tokenTtlMinutes + " minutes:\n"
                + link + "\n\n"
                + "If you didn't ask for this, you can ignore this email. Your password won't change.\n";
        String safeLink = HtmlUtils.htmlEscape(link);
        String html = "<p>Someone asked to reset the password for your Classic Chat Reader account.</p>"
                + "<p><a href=\"" + safeLink + "\">Choose a new password</a></p>"
                + "<p>This link works once and expires in " + tokenTtlMinutes + " minutes.</p>"
                + "<p>If you didn't ask for this, you can ignore this email. Your password won't change.</p>";
        if (!emailSender.send(recipient, subject, text, html)) {
            log.warn("Password reset email was not sent");
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private String newToken() {
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
