package com.classicchatreader.service;

import jakarta.servlet.http.Cookie;
import com.classicchatreader.entity.PendingExternalIdentityLinkEntity;
import com.classicchatreader.entity.UserAuthIdentityEntity;
import com.classicchatreader.entity.UserEntity;
import com.classicchatreader.entity.UserLocalCredentialEntity;
import com.classicchatreader.entity.UserSessionEntity;
import com.classicchatreader.repository.PendingExternalIdentityLinkRepository;
import com.classicchatreader.repository.UserAuthIdentityRepository;
import com.classicchatreader.repository.UserLocalCredentialRepository;
import com.classicchatreader.repository.UserRepository;
import com.classicchatreader.repository.UserSessionRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

    @Mock
    private PendingExternalIdentityLinkRepository pendingExternalIdentityLinkRepository;

    private AccountAuthService accountAuthService;

    @BeforeEach
    void setUp() {
        accountAuthService = new AccountAuthService(
                userRepository,
                userLocalCredentialRepository,
                userAuthIdentityRepository,
                userSessionRepository,
                pendingExternalIdentityLinkRepository,
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
    void signInWithExternalIdentity_existingPasswordAccount_doesNotSilentlyLinkOrCreateSession() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("reader@example.com");

        UserLocalCredentialEntity credential = new UserLocalCredentialEntity();
        credential.setUser(user);
        credential.setUserId("user-1");
        credential.setPasswordHash(BCrypt.hashpw("password123", BCrypt.gensalt(10)));

        when(userAuthIdentityRepository.findByProviderAndProviderSubject("google", "google-subject"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.of(user));
        when(userLocalCredentialRepository.findByUserId("user-1")).thenReturn(Optional.of(credential));
        when(pendingExternalIdentityLinkRepository.save(any(PendingExternalIdentityLinkEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = accountAuthService.signInWithExternalIdentity(
                new AccountAuthService.ExternalIdentity("google", "google-subject", "reader@example.com", true),
                response
        );

        assertEquals(AccountAuthService.ResultStatus.EXTERNAL_IDENTITY_LINK_REQUIRED, result.status());
        assertFalse(result.authenticated());
        assertEquals("reader@example.com", result.email());
        verify(userAuthIdentityRepository, never()).save(any(UserAuthIdentityEntity.class));
        verify(userSessionRepository, never()).save(any(UserSessionEntity.class));
        assertTrue(response.getHeader("Set-Cookie").contains("pdr_account_google_link="));
        assertFalse(response.getHeader("Set-Cookie").contains("pdr_account_session="));

        ArgumentCaptor<PendingExternalIdentityLinkEntity> pendingCaptor =
                ArgumentCaptor.forClass(PendingExternalIdentityLinkEntity.class);
        verify(pendingExternalIdentityLinkRepository).save(pendingCaptor.capture());
        assertEquals("user-1", pendingCaptor.getValue().getUserId());
        assertEquals("google", pendingCaptor.getValue().getProvider());
        assertEquals("google-subject", pendingCaptor.getValue().getProviderSubject());
    }

    @Test
    void confirmExternalIdentityLink_passwordReAuth_linksIdentityAndInvalidatesSessions() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("reader@example.com");

        UserLocalCredentialEntity credential = new UserLocalCredentialEntity();
        credential.setUser(user);
        credential.setUserId("user-1");
        credential.setPasswordHash(BCrypt.hashpw("password123", BCrypt.gensalt(10)));

        PendingExternalIdentityLinkEntity pending = new PendingExternalIdentityLinkEntity();
        pending.setTokenHash(hash("pending-token"));
        pending.setUserId("user-1");
        pending.setProvider("google");
        pending.setProviderSubject("google-subject");
        pending.setEmail("reader@example.com");
        pending.setExpiresAt(LocalDateTime.now().plusMinutes(10));

        AtomicReference<UserSessionEntity> storedSession = new AtomicReference<>();
        when(pendingExternalIdentityLinkRepository.findByTokenHash(hash("pending-token")))
                .thenReturn(Optional.of(pending));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userLocalCredentialRepository.findByUserId("user-1")).thenReturn(Optional.of(credential));
        when(userAuthIdentityRepository.save(any(UserAuthIdentityEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userSessionRepository.save(any(UserSessionEntity.class))).thenAnswer(invocation -> {
            UserSessionEntity session = invocation.getArgument(0);
            storedSession.set(session);
            return session;
        });

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pdr_account_google_link", "pending-token"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = accountAuthService.confirmExternalIdentityLink(
                "password123",
                request,
                response
        );

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, result.status());
        assertTrue(result.authenticated());
        assertEquals("reader@example.com", result.email());
        assertNotNull(storedSession.get());

        ArgumentCaptor<UserAuthIdentityEntity> identityCaptor = ArgumentCaptor.forClass(UserAuthIdentityEntity.class);
        verify(userAuthIdentityRepository).save(identityCaptor.capture());
        assertEquals("google", identityCaptor.getValue().getProvider());
        assertEquals("google-subject", identityCaptor.getValue().getProviderSubject());
        assertEquals("reader@example.com", identityCaptor.getValue().getEmail());

        var order = inOrder(userSessionRepository);
        order.verify(userSessionRepository).deleteByUser_Id("user-1");
        order.verify(userSessionRepository).save(any(UserSessionEntity.class));
        verify(pendingExternalIdentityLinkRepository).deleteByTokenHash(hash("pending-token"));
    }

    @Test
    void confirmExternalIdentityLink_wrongPassword_doesNotLinkOrInvalidateSessions() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("reader@example.com");

        UserLocalCredentialEntity credential = new UserLocalCredentialEntity();
        credential.setUser(user);
        credential.setUserId("user-1");
        credential.setPasswordHash(BCrypt.hashpw("password123", BCrypt.gensalt(10)));

        PendingExternalIdentityLinkEntity pending = new PendingExternalIdentityLinkEntity();
        pending.setTokenHash(hash("pending-token"));
        pending.setUserId("user-1");
        pending.setProvider("google");
        pending.setProviderSubject("google-subject");
        pending.setEmail("reader@example.com");
        pending.setExpiresAt(LocalDateTime.now().plusMinutes(10));

        when(pendingExternalIdentityLinkRepository.findByTokenHash(hash("pending-token")))
                .thenReturn(Optional.of(pending));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userLocalCredentialRepository.findByUserId("user-1")).thenReturn(Optional.of(credential));
        when(userLocalCredentialRepository.save(any(UserLocalCredentialEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pdr_account_google_link", "pending-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AccountAuthService.AuthResult result = accountAuthService.confirmExternalIdentityLink(
                "wrong-password",
                request,
                response
        );

        assertEquals(AccountAuthService.ResultStatus.INVALID_CREDENTIALS, result.status());
        verify(userAuthIdentityRepository, never()).save(any(UserAuthIdentityEntity.class));
        verify(userSessionRepository, never()).deleteByUser_Id(anyString());
        verify(userSessionRepository, never()).save(any(UserSessionEntity.class));
    }

    @Test
    void signInWithExternalIdentity_existingLinkedIdentity_createsSession() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("reader@example.com");

        UserAuthIdentityEntity identity = new UserAuthIdentityEntity();
        identity.setUser(user);
        identity.setProvider("google");
        identity.setProviderSubject("google-subject");
        identity.setEmail("reader@example.com");
        identity.setEmailVerified(true);

        AtomicReference<UserSessionEntity> storedSession = new AtomicReference<>();
        when(userAuthIdentityRepository.findByProviderAndProviderSubject("google", "google-subject"))
                .thenReturn(Optional.of(identity));
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
        verify(userAuthIdentityRepository, never()).save(any(UserAuthIdentityEntity.class));
        verify(pendingExternalIdentityLinkRepository, never()).save(any(PendingExternalIdentityLinkEntity.class));
    }

    @Test
    void signInWithExternalIdentity_unknownEmail_createsUserIdentityAndSession() {
        when(userAuthIdentityRepository.findByProviderAndProviderSubject("google", "google-subject"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId("user-1");
            return user;
        });
        when(userAuthIdentityRepository.save(any(UserAuthIdentityEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userSessionRepository.save(any(UserSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MockHttpServletResponse response = new MockHttpServletResponse();
        AccountAuthService.AuthResult result = accountAuthService.signInWithExternalIdentity(
                new AccountAuthService.ExternalIdentity("google", "google-subject", "reader@example.com", true),
                response
        );

        assertEquals(AccountAuthService.ResultStatus.SUCCESS, result.status());
        verify(userAuthIdentityRepository).save(any(UserAuthIdentityEntity.class));
        verify(userSessionRepository).save(any(UserSessionEntity.class));
        verify(pendingExternalIdentityLinkRepository, never()).save(any(PendingExternalIdentityLinkEntity.class));
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
                pendingExternalIdentityLinkRepository,
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
                pendingExternalIdentityLinkRepository,
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
