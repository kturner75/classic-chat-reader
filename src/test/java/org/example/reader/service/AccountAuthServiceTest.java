package org.example.reader.service;

import jakarta.servlet.http.Cookie;
import org.example.reader.entity.UserAuthIdentityEntity;
import org.example.reader.entity.UserEntity;
import org.example.reader.entity.UserLocalCredentialEntity;
import org.example.reader.entity.UserSessionEntity;
import org.example.reader.repository.UserAuthIdentityRepository;
import org.example.reader.repository.UserLocalCredentialRepository;
import org.example.reader.repository.UserRepository;
import org.example.reader.repository.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountAuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserLocalCredentialRepository userLocalCredentialRepository;

    @Mock
    private UserAuthIdentityRepository userAuthIdentityRepository;

    @Mock
    private UserSessionRepository userSessionRepository;

    private AccountAuthService accountAuthService;

    @BeforeEach
    void setUp() {
        accountAuthService = new AccountAuthService(
                userRepository,
                userLocalCredentialRepository,
                userAuthIdentityRepository,
                userSessionRepository,
                true,
                "optional",
                "",
                "pdr_account_session",
                60,
                false,
                10,
                10
        );
    }

    @Test
    void register_validCredentials_createsUserCredentialsAndSessionCookie() {
        AtomicReference<UserSessionEntity> storedSession = new AtomicReference<>();
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId("user-1");
            return user;
        });
        when(userLocalCredentialRepository.save(any(UserLocalCredentialEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userSessionRepository.save(any(UserSessionEntity.class))).thenAnswer(invocation -> {
            UserSessionEntity session = invocation.getArgument(0);
            storedSession.set(session);
            return session;
        });

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = accountAuthService.register(
                "Reader@Example.com",
                "password123",
                response
        );

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, result.status());
        assertTrue(result.authenticated());
        assertEquals("reader@example.com", result.email());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals("reader@example.com", userCaptor.getValue().getEmail());

        ArgumentCaptor<UserLocalCredentialEntity> credentialCaptor = ArgumentCaptor.forClass(UserLocalCredentialEntity.class);
        verify(userLocalCredentialRepository).save(credentialCaptor.capture());
        assertTrue(BCrypt.checkpw("password123", credentialCaptor.getValue().getPasswordHash()));

        assertNotNull(storedSession.get());
        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("pdr_account_session="));
    }

    @Test
    void status_withValidSessionCookie_returnsAuthenticated() {
        AtomicReference<UserSessionEntity> storedSession = new AtomicReference<>();
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId("user-1");
            return user;
        });
        when(userLocalCredentialRepository.save(any(UserLocalCredentialEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userSessionRepository.save(any(UserSessionEntity.class))).thenAnswer(invocation -> {
            UserSessionEntity session = invocation.getArgument(0);
            storedSession.set(session);
            return session;
        });
        when(userSessionRepository.findByTokenHash(anyString())).thenAnswer(invocation -> {
            String requestedHash = invocation.getArgument(0);
            UserSessionEntity session = storedSession.get();
            if (session != null && session.getTokenHash().equals(requestedHash)) {
                return Optional.of(session);
            }
            return Optional.empty();
        });

        MockHttpServletResponse registerResponse = new MockHttpServletResponse();
        accountAuthService.register("reader@example.com", "password123", registerResponse);
        String token = extractCookieValue(registerResponse.getHeader("Set-Cookie"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pdr_account_session", token));

        AccountAuthService.AuthResult status = accountAuthService.status(request);

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, status.status());
        assertTrue(status.authenticated());
        assertEquals("reader@example.com", status.email());
    }

    @Test
    void login_unknownEmail_returnsInvalidCredentials() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = accountAuthService.login(
                "reader@example.com",
                "wrong-password",
                response
        );

        assertEquals(AccountAuthService.ResultStatus.INVALID_CREDENTIALS, result.status());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
    }

    @Test
    void login_googleOnlyAccount_returnsInvalidCredentials() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("reader@example.com");
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(userLocalCredentialRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = accountAuthService.login(
                "reader@example.com",
                "password123",
                response
        );

        assertEquals(AccountAuthService.ResultStatus.INVALID_CREDENTIALS, result.status());
    }

    @Test
    void login_repeatedInvalidCredentials_triggersLockoutWithRetryAfter() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("reader@example.com");

        UserLocalCredentialEntity credential = new UserLocalCredentialEntity();
        credential.setUser(user);
        credential.setUserId("user-1");
        credential.setPasswordHash(BCrypt.hashpw("password123", BCrypt.gensalt(10)));

        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(userLocalCredentialRepository.findByUserId("user-1")).thenReturn(Optional.of(credential));
        when(userLocalCredentialRepository.save(any(UserLocalCredentialEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 4; i++) {
            AccountAuthService.AuthResult attempt = accountAuthService.login(
                    "reader@example.com",
                    "wrong-password",
                    response
            );
            assertEquals(AccountAuthService.ResultStatus.INVALID_CREDENTIALS, attempt.status());
        }

        AccountAuthService.AuthResult locked = accountAuthService.login(
                "reader@example.com",
                "wrong-password",
                response
        );

        assertEquals(AccountAuthService.ResultStatus.ACCOUNT_LOCKED, locked.status());
        assertEquals(30, locked.retryAfterSeconds());
    }

    @Test
    void login_successClearsPreviousLockoutState() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("reader@example.com");

        UserLocalCredentialEntity credential = new UserLocalCredentialEntity();
        credential.setUser(user);
        credential.setUserId("user-1");
        credential.setPasswordHash(BCrypt.hashpw("password123", BCrypt.gensalt(10)));
        credential.setFailedLoginAttempts(6);
        credential.setLoginLockedUntil(LocalDateTime.now().minusSeconds(5));

        AtomicReference<UserSessionEntity> storedSession = new AtomicReference<>();
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(userLocalCredentialRepository.findByUserId("user-1")).thenReturn(Optional.of(credential));
        when(userLocalCredentialRepository.save(any(UserLocalCredentialEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userSessionRepository.save(any(UserSessionEntity.class))).thenAnswer(invocation -> {
            UserSessionEntity session = invocation.getArgument(0);
            storedSession.set(session);
            return session;
        });

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = accountAuthService.login(
                "reader@example.com",
                "password123",
                response
        );

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, result.status());
        assertEquals(0, credential.getFailedLoginAttempts());
        assertNull(credential.getLoginLockedUntil());
        assertNotNull(storedSession.get());
    }

    @Test
    void signInWithExternalIdentity_existingEmail_linksIdentityAndCreatesSession() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("reader@example.com");

        AtomicReference<UserSessionEntity> storedSession = new AtomicReference<>();
        when(userAuthIdentityRepository.findByProviderAndProviderSubject("google", "google-subject"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(userAuthIdentityRepository.save(any(UserAuthIdentityEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userSessionRepository.save(any(UserSessionEntity.class))).thenAnswer(invocation -> {
            UserSessionEntity session = invocation.getArgument(0);
            storedSession.set(session);
            return session;
        });

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = accountAuthService.signInWithExternalIdentity(
                new AccountAuthService.ExternalIdentity("google", "google-subject", "reader@example.com", true),
                response
        );

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, result.status());
        assertEquals("reader@example.com", result.email());
        assertNotNull(storedSession.get());

        ArgumentCaptor<UserAuthIdentityEntity> identityCaptor = ArgumentCaptor.forClass(UserAuthIdentityEntity.class);
        verify(userAuthIdentityRepository).save(identityCaptor.capture());
        assertEquals("google", identityCaptor.getValue().getProvider());
        assertEquals("google-subject", identityCaptor.getValue().getProviderSubject());
        assertEquals("reader@example.com", identityCaptor.getValue().getEmail());
    }

    @Test
    void logout_clearsCookieAndDeletesSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pdr_account_session", "token-abc"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AccountAuthService.AuthResult result = accountAuthService.logout(request, response);

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, result.status());
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        verify(userSessionRepository).deleteByTokenHash(hash("token-abc"));
    }

    @Test
    void register_internalRollout_rejectsEmailNotInAllowList() {
        AccountAuthService internalRolloutService = new AccountAuthService(
                userRepository,
                userLocalCredentialRepository,
                userAuthIdentityRepository,
                userSessionRepository,
                true,
                "internal",
                "tester@example.com",
                "pdr_account_session",
                60,
                false,
                10,
                10
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = internalRolloutService.register(
                "reader@example.com",
                "password123",
                response
        );

        assertEquals(AccountAuthService.ResultStatus.ROLLOUT_RESTRICTED, result.status());
    }

    @Test
    void register_internalRollout_allowsEmailInAllowList() {
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId("user-1");
            return user;
        });
        when(userLocalCredentialRepository.save(any(UserLocalCredentialEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userSessionRepository.save(any(UserSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountAuthService internalRolloutService = new AccountAuthService(
                userRepository,
                userLocalCredentialRepository,
                userAuthIdentityRepository,
                userSessionRepository,
                true,
                "internal",
                "tester@example.com",
                "pdr_account_session",
                60,
                false,
                10,
                10
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = internalRolloutService.register(
                "tester@example.com",
                "password123",
                response
        );

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, result.status());
    }

    private String extractCookieValue(String setCookieHeader) {
        return setCookieHeader.split(";", 2)[0].split("=", 2)[1];
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
