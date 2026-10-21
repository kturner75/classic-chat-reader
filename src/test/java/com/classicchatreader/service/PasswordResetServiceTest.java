package com.classicchatreader.service;

import com.classicchatreader.entity.UserEntity;
import com.classicchatreader.repository.PasswordResetTokenRepository;
import com.classicchatreader.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Flyway schema and real transactions (no test transaction), so the email only goes out
 * after the token row commits, exactly as in production. A direct executor sends it on the
 * calling thread, so "no mail" assertions need no waiting.
 */
@DataJpaTest
@Import({AccountAuthService.class, PasswordResetServiceTest.MailConfig.class})
@TestPropertySource(properties = {
        "account.auth.enabled=true",
        "account.auth.rollout.mode=optional",
        "account.auth.password.bcrypt-strength=10"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PasswordResetServiceTest {

    private static final Pattern LINK = Pattern.compile("https://reader\\.example/#password_reset=([A-Za-z0-9_-]+)");

    @Autowired private PasswordResetService passwordResetService;
    @Autowired private AccountAuthService accountAuthService;
    @Autowired private UserRepository userRepository;
    @Autowired private CapturingSender sender;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void clearMail() {
        sender.sent.clear();
    }

    @AfterEach
    void clean() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("password_reset_tokens", "user_sessions", "pending_external_identity_links",
                "user_auth_identities", "user_local_credentials", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void unknownEmailGetsNoMailAndNoToken() {
        passwordResetService.requestReset("nobody@school.edu");

        assertTrue(sender.sent.isEmpty());
        assertEquals(0, tokenCount());
    }

    @Test
    void resetLinkSetsNewPasswordSignsInAndCannotBeReused() throws Exception {
        register("student@columbiastate.edu", "original-password");

        passwordResetService.requestReset("  Student@ColumbiaState.edu ");
        String token = awaitToken();
        assertEquals("student@columbiastate.edu", sender.sent.get(0).to());
        assertEquals(0, new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM password_reset_tokens WHERE token_hash = ?", Integer.class, token),
                "only the hash is stored");
        assertTrue(passwordResetService.isTokenValid(token));

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = passwordResetService.completeReset(token, "brand-new-password", response);

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, result.status());
        assertTrue(response.getHeaders("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.startsWith("pdr_account_session=") && !cookie.startsWith("pdr_account_session=;")),
                "signed in after reset");
        assertEquals(1, sessionCount(), "the older session from registering is gone; only the new one remains");
        assertEquals(AccountAuthService.ResultStatus.INVALID_CREDENTIALS,
                accountAuthService.login("student@columbiastate.edu", "original-password", new MockHttpServletResponse()).status());
        assertEquals(AccountAuthService.ResultStatus.SUCCESS,
                accountAuthService.login("student@columbiastate.edu", "brand-new-password", new MockHttpServletResponse()).status());

        assertFalse(passwordResetService.isTokenValid(token));
        assertEquals(AccountAuthService.ResultStatus.INVALID_CREDENTIALS,
                passwordResetService.completeReset(token, "another-password", new MockHttpServletResponse()).status());
    }

    @Test
    void repeatRequestsInsideTheCooldownSendOneEmail() throws Exception {
        register("teacher@columbiastate.edu", "original-password");

        passwordResetService.requestReset("teacher@columbiastate.edu");
        awaitToken();
        passwordResetService.requestReset("teacher@columbiastate.edu");

        assertEquals(1, sender.sent.size());
        assertEquals(1, tokenCount());
    }

    @Test
    void simultaneousSubmitsOfOneLinkSucceedOnce() throws Exception {
        register("race@example.com", "original-password");
        passwordResetService.requestReset("race@example.com");
        String token = awaitToken();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AccountAuthService.ResultStatus>> results = List.of("first-new-password", "second-new-password").stream()
                    .map(password -> pool.submit(() -> {
                        start.await();
                        return passwordResetService.completeReset(token, password, new MockHttpServletResponse()).status();
                    }))
                    .toList();
            start.countDown();
            long successes = 0;
            for (Future<AccountAuthService.ResultStatus> result : results) {
                if (result.get(10, TimeUnit.SECONDS) == AccountAuthService.ResultStatus.SUCCESS) {
                    successes++;
                }
            }
            assertEquals(1, successes, "a reset link is single-use even when submitted twice at once");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tooShortPasswordKeepsTheLinkUsable() throws Exception {
        register("reader@example.com", "original-password");
        passwordResetService.requestReset("reader@example.com");
        String token = awaitToken();

        assertEquals(AccountAuthService.ResultStatus.INVALID_PASSWORD,
                passwordResetService.completeReset(token, "short", new MockHttpServletResponse()).status());
        assertTrue(passwordResetService.isTokenValid(token));
    }

    @Test
    void expiredLinkIsRejected() throws Exception {
        register("late@example.com", "original-password");
        passwordResetService.requestReset("late@example.com");
        String token = awaitToken();
        new JdbcTemplate(dataSource).update("UPDATE password_reset_tokens SET expires_at = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP)");

        assertFalse(passwordResetService.isTokenValid(token));
        assertEquals(AccountAuthService.ResultStatus.INVALID_CREDENTIALS,
                passwordResetService.completeReset(token, "brand-new-password", new MockHttpServletResponse()).status());
    }

    @Test
    void googleOnlyAccountCanSetAFirstPassword() throws Exception {
        UserEntity user = new UserEntity();
        user.setEmail("google-only@gmail.com");
        userRepository.save(user);

        passwordResetService.requestReset("google-only@gmail.com");
        String token = awaitToken();

        assertEquals(AccountAuthService.ResultStatus.SUCCESS,
                passwordResetService.completeReset(token, "first-password", new MockHttpServletResponse()).status());
        assertEquals(AccountAuthService.ResultStatus.SUCCESS,
                accountAuthService.login("google-only@gmail.com", "first-password", new MockHttpServletResponse()).status());
    }

    private void register(String email, String password) {
        assertEquals(AccountAuthService.ResultStatus.SUCCESS,
                accountAuthService.register(email, password, new MockHttpServletResponse()).status());
    }

    /** Pulls the token out of the link in the last email sent. */
    private String awaitToken() {
        assertFalse(sender.sent.isEmpty(), "reset email was sent");
        Matcher matcher = LINK.matcher(sender.sent.get(sender.sent.size() - 1).text());
        assertTrue(matcher.find(), "email carries the reset link on the configured base URL");
        return matcher.group(1);
    }

    private int tokenCount() {
        return new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM password_reset_tokens", Integer.class);
    }

    private int sessionCount() {
        return new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM user_sessions", Integer.class);
    }

    record Mail(String to, String subject, String text, String html) {
    }

    static class CapturingSender implements TransactionalEmailSender {
        final List<Mail> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean send(String to, String subject, String textBody, String htmlBody) {
            sent.add(new Mail(to, subject, textBody, htmlBody));
            return true;
        }
    }

    @TestConfiguration
    static class MailConfig {
        @Bean
        CapturingSender capturingSender() {
            return new CapturingSender();
        }

        @Bean
        PasswordResetService passwordResetService(
                PasswordResetTokenRepository tokenRepository,
                UserRepository userRepository,
                AccountAuthService accountAuthService,
                CapturingSender sender) {
            ExecutorService direct = new AbstractExecutorService() {
                private volatile boolean shutdown;
                @Override public void execute(Runnable command) { command.run(); }
                @Override public void shutdown() { shutdown = true; }
                @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
                @Override public boolean isShutdown() { return shutdown; }
                @Override public boolean isTerminated() { return shutdown; }
                @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            };
            return new PasswordResetService(tokenRepository, userRepository, accountAuthService, sender,
                    "https://reader.example/", 30, direct);
        }
    }
}
